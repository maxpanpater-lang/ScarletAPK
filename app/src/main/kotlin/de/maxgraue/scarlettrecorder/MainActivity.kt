package de.maxgraue.scarlettrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.AudioManager
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import de.maxgraue.scarlettrecorder.audio.UsbAudioManager
import de.maxgraue.scarlettrecorder.audio.AudioRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.MediaPlayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.maxgraue.scarlettrecorder.audio.Mp3Exporter
import de.maxgraue.scarlettrecorder.audio.LooperEngine
import de.maxgraue.scarlettrecorder.audio.LooperState

private val Bg = Color(0xFFF4F5F7)
private val Card = Color(0xFFFFFFFF)
private val DarkBg = Color(0xFF101114)
private val DarkCard = Color(0xFF1B1D22)
private val Scarlett = Color(0xFFE3222E)

class MainActivity : ComponentActivity() {
    private lateinit var usbAudio: UsbAudioManager
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        usbAudio = UsbAudioManager(this)
        usbAudio.start()
        setContent { ScarlettRecorderApp(usbAudio) }
    }
    override fun onDestroy() { usbAudio.stop(); super.onDestroy() }
}

@Composable fun ScarlettRecorderApp(usbAudio: UsbAudioManager) {
    val context = LocalContext.current
    val recorder = RecordingService.recorder
    val looper = remember { LooperEngine() }
    DisposableEffect(looper) { onDispose { looper.stop() } }
    val recording by RecordingService.recordingActive.collectAsState()
    val monitoringActive by RecordingService.monitoringActive.collectAsState()
    var page by remember { mutableStateOf("Recorder") }
    var mode by remember { mutableStateOf("Stereo") }
    var monitoring by remember { mutableStateOf(false) }
    var recorderMetronome by remember { mutableStateOf(recorder.metronomeEnabled) }
    var recorderBpm by remember { mutableStateOf(120f) }
    var themeMode by remember { mutableStateOf(0) } // 0 system, 1 light, 2 dark
    var showRecordings by remember { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    val player = remember { MediaPlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    if (showRecordings) {
        val folder = context.getExternalFilesDir("recordings") ?: context.filesDir
        val files = remember(revision, showRecordings) { folder.listFiles()?.filter { it.extension in listOf("wav", "mp3") }?.sortedByDescending { it.lastModified() }.orEmpty() }
        AlertDialog(onDismissRequest = { player.reset(); showRecordings = false },
            title = { Text("Recordings") },
            text = {
                LazyColumn {
                    item { Text(if (exporting) "Exporting MP3 …" else exportMessage) }
                    items(files) { file ->
                        TextButton(onClick = {
                            runCatching {
                                player.reset()
                                player.setDataSource(file.absolutePath)
                                player.prepare()
                                player.start()
                            }.onFailure { recorder.error.value = "Playback failed" }
                        }) { Text(file.name + " · " + file.length() / 1024 + " KB") }
                        TextButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType(if (file.extension == "mp3") "audio/mpeg" else "audio/wav")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(share, "Share recording"))
                        }) { Text("Share") }
                        if (file.extension == "wav") TextButton(enabled = !exporting, onClick = {
                            exporting = true
                            exportScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    Mp3Exporter.export(file, File(file.parentFile, file.nameWithoutExtension + "-${System.currentTimeMillis()}.mp3"))
                                }
                                exportMessage = result.fold({ "MP3 saved · 192 kbps" }, { "MP3 export failed: ${it.message}" })
                                revision++
                                exporting = false
                            }
                        }) { Text("Export as MP3") }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)) }) { Text("Open folder") }
                    TextButton(onClick = { player.reset(); showRecordings = false }) { Text("Close") }
                }
            })
    }
    val levels by recorder.levels.collectAsState()
    val seconds by recorder.elapsedSeconds.collectAsState()
    val audioError by recorder.error.collectAsState()
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(primary = Scarlett, background = DarkBg, surface = DarkCard) else lightColorScheme(primary = Scarlett, background = Bg, surface = Card)
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Recorder", "Looper", "Mixer", "Effects", "Recordings").forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Text(item.take(1), fontWeight = FontWeight.Bold) },
                            label = { Text(item, fontSize = 10.sp) }
                        )
                    }
                }

            }
        ) { insets ->
            Column(Modifier.padding(insets).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SCARLETT", color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    TextButton(onClick = { themeMode = (themeMode + 1) % 3 }) { Text(if (themeMode == 1) "LIGHT" else if (themeMode == 2) "DARK" else "SYSTEM", fontSize = 10.sp) }
                }
                if (page == "Looper") { LooperPage(context, looper); return@Column }
                if (page == "Mixer") { MixerPage(); return@Column }
                if (page == "Effects") { EffectsPage(recorder); return@Column }
                                if (page == "Recordings") { TextButton(onClick = { showRecordings = true }) { Text("Open recordings") }; return@Column }
                val device by usbAudio.device.collectAsState()
                Surface(color = if (device != null) Color(0xFF278839).copy(alpha = .18f) else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    TextButton(onClick = { usbAudio.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (device != null) "●  USB Audio Connected" else "○  Connect USB audio interface",
                            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (!monitoringActive || recording) Text(
                    "%02d:%02d".format(seconds / 60, seconds % 60),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    fontSize = 60.sp, fontWeight = FontWeight.Bold)
                ChannelMeter("INPUT 1", "Mono", levels.first)
                ChannelMeter("INPUT 2", "Mono", levels.second)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (monitoringActive && !recording) "Input routing" else "Recording format", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) { listOf("Stereo", "Mono 1", "Mono 2", "Getrennt").forEach { value -> FilterChip(modifier = Modifier.weight(1f), enabled = !recording, selected = mode == value, onClick = { if (monitoringActive) { mode = value; val restart = android.content.Intent(context, RecordingService::class.java).setAction("MONITOR").putExtra("mode", value); androidx.core.content.ContextCompat.startForegroundService(context, restart) } else mode = value }, label = { Text(if (value == "Getrennt") "Split" else value, fontSize = 10.sp, maxLines = 1) }) } }
                        Text("48 kHz · 16 bit · WAV and MP3 export", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = if (recording) monitoring else monitoringActive, onCheckedChange = {
                        monitoring = it
                        recorder.monitorEnabled = it
                        val intent = android.content.Intent(context, RecordingService::class.java)
                        if (it && !recording) androidx.core.content.ContextCompat.startForegroundService(context, intent.setAction("MONITOR").putExtra("mode", mode))
                        else if (!it && !recording) context.startService(intent.setAction("STOP"))
                    })
                    Text("USB monitoring")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = recorderMetronome, onCheckedChange = { recorderMetronome = it; recorder.metronomeEnabled = it; recorder.bpm = recorderBpm })
                    Text("Metronome")
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = recorderBpm.toInt().toString(), onValueChange = { it.toFloatOrNull()?.let { value -> recorderBpm = value.coerceIn(40f, 240f); recorder.bpm = recorderBpm } }, label = { Text("BPM") }, singleLine = true, modifier = Modifier.width(100.dp))
                }
                Text(if (audioError.isNotEmpty()) audioError else if (recording) "Recording · ${seconds}s" else if (monitoringActive) "Monitoring active" else "Ready", color = if (recording || monitoringActive) Scarlett else Color.Gray)
                if (!monitoringActive || recording) Button(onClick = {
                    val intent = android.content.Intent(context, RecordingService::class.java)
                    if (recording) context.startService(intent.setAction("STOP"))
                    else runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(context, intent.putExtra("mode", mode))
                    }.onFailure { recorder.error.value = it.message ?: "Start failed" }
                }, modifier = Modifier.align(Alignment.CenterHorizontally).size(104.dp).semantics { contentDescription = if (recording) "Stop recording" else "Start recording" }, colors = ButtonDefaults.buttonColors(containerColor = Scarlett), shape = CircleShape) { Text(if (recording) "■" else "●", color = Color.White, fontSize = 42.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !recording && !monitoringActive, onClick = { showRecordings = true }) { Text("Open recordings") }
                    OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)) }) { Text("Open folder") }
                }
            }
        }
    }
}

@Composable private fun LooperPage(context: android.content.Context, looper: LooperEngine) {
    val state by looper.state.collectAsState()
    val length by looper.loopSeconds.collectAsState()
    val progress by looper.loopProgress.collectAsState()
    var bpm by remember { mutableStateOf(120f) }
    var metronomeOn by remember { mutableStateOf(false) }
    var selectedSlot by remember { mutableStateOf(0) }
    val slotStates = remember { mutableStateListOf(*Array(4) { LooperState.IDLE }) }
    val slotLengths = remember { mutableStateListOf(*Array(4) { 0f }) }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LIVE LOOPER", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Tap: record/stop · double-tap: overdub · hold: delete", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("BPM ${bpm.toInt()}", color = MaterialTheme.colorScheme.onSurface)
            Slider(value = bpm, onValueChange = { bpm = it; looper.bpm = it }, valueRange = 40f..220f)

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(2) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(2) { column ->
                    val slot = row * 2 + column
                    val active = selectedSlot == slot
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .pointerInput(slot, length) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        selectedSlot = slot
                                        if (slotStates[slot] == LooperState.PLAYING) {
                                            looper.toggleOverdub(slot)
                                            slotStates[slot] = LooperState.OVERDUBBING
                                        }
                                    },
                                    onLongPress = {
                                        if (selectedSlot == slot) looper.clear(slot)
                                        slotStates[slot] = LooperState.IDLE
                                        slotLengths[slot] = 0f
                                    },
                                    onTap = {
                                        selectedSlot = slot
                                        when (slotStates[slot]) {
                                            LooperState.IDLE -> {
                                                val audio = context.getSystemService(AudioManager::class.java)
                                                val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                                                val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                                                if (input != null && output != null && looper.start(input, output, slot)) slotStates[slot] = LooperState.RECORDING
                                            }
                                            LooperState.RECORDING -> {
                                                looper.finishLoop(slot)
                                                slotStates[slot] = LooperState.PLAYING
                                                slotLengths[slot] = length
                                            }
                                            LooperState.PLAYING, LooperState.OVERDUBBING -> {
                                                looper.toggleOverdub(slot)
                                                slotStates[slot] = if (slotStates[slot] == LooperState.OVERDUBBING) LooperState.PLAYING else LooperState.OVERDUBBING
                                            }
                                        }
                                    }
                                )
                            }
                            .background(
                                when (slotStates[slot]) {
                                    LooperState.RECORDING -> Scarlett.copy(alpha = .18f)
                                    LooperState.PLAYING -> Color(0xFF36B637).copy(alpha = .18f)
                                    LooperState.OVERDUBBING -> Color(0xFFFFB020).copy(alpha = .18f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                            drawArc(Color(0xFF3A3D44), -90f, 360f, false, style = Stroke(10f, cap = StrokeCap.Round))
                            val sweep = if (active) progress * 360f else if (slotLengths[slot] > 0f) 360f else 0f
                            drawArc(when (slotStates[slot]) { LooperState.RECORDING -> Scarlett; LooperState.OVERDUBBING -> Color(0xFFFFB020); else -> Color(0xFF62C978) }, -90f, sweep, false, style = Stroke(10f, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${slot + 1}", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(when (slotStates[slot]) { LooperState.IDLE -> "Empty"; LooperState.RECORDING -> "Recording"; LooperState.PLAYING -> "Playing"; LooperState.OVERDUBBING -> "Overdub" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (slotLengths[slot] > 0f) Text("%.2f s".format(slotLengths[slot]), fontSize = 14.sp)
                        }
                    }
                }
            }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Selected loop: ${selectedSlot + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.2f".format(length)} s", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { metronomeOn = !metronomeOn; looper.metronomeEnabled = metronomeOn; looper.bpm = bpm }) {
                    Text(if (metronomeOn) "STOP METRONOME" else "METRONOME")
                }
                OutlinedButton(onClick = {
                    looper.clear(selectedSlot)
                    slotStates[selectedSlot] = LooperState.IDLE
                    slotLengths[selectedSlot] = 0f
                }) { Text("CLEAR") }
            }
            OutlinedButton(onClick = {
                looper.stop()
                repeat(4) { slot -> looper.clear(slot); slotStates[slot] = LooperState.IDLE; slotLengths[slot] = 0f }
            }, modifier = Modifier.fillMaxWidth()) { Text("■  Stop all & clear") }
            Text("State: ${state.name} · Loop length: ${"%.2f".format(length)} s", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun MixerPage() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LOOP MIXER", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Mixer controls are not connected yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(4) { index ->
                Text("Loop ${index + 1}")
                Slider(value = .8f, onValueChange = {}, enabled = false)
            }
        }
    }
}

@Composable private fun EffectsPage(recorder: AudioRecorder) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("EFFECTS", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Loop effects are not connected yet.", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("USB monitoring is unprocessed. No gate, EQ or compressor is applied.", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun ChannelMeter(name: String, type: String, level: Float) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold); Text(if (level > 0f) "%.1f dBFS".format(20 * kotlin.math.log10(level)) else "−∞ dBFS", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(12.dp), color = if (level > .85f) Color.Red else Color(0xFF62C978), trackColor = Color(0xFF303238))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(type, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Text("Monitoring", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
    }
}
