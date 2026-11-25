package de.maxgraue.scarlettrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.AudioManager
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

private val Bg = Color(0xFF101114)
private val Card = Color(0xFF1B1D22)
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
    val recording by RecordingService.recordingActive.collectAsState()
    val monitoringActive by RecordingService.monitoringActive.collectAsState()
    var page by remember { mutableStateOf("Recorder") }
    var mode by remember { mutableStateOf("Stereo") }
    var monitoring by remember { mutableStateOf(false) }
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
            confirmButton = { TextButton(onClick = { player.reset(); showRecordings = false }) { Text("Close") } })
    }
    val levels by recorder.levels.collectAsState()
    val seconds by recorder.elapsedSeconds.collectAsState()
    val audioError by recorder.error.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme(primary = Scarlett, background = Bg, surface = Card)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${page.uppercase()}", color = Scarlett, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Recorder", "Looper", "Mixer", "Effects", "Recordings").forEach { item ->
                        FilterChip(selected = page == item, onClick = { page = item }, label = { Text(item, fontSize = 11.sp) })
                    }
                }
                if (page == "Looper") { LooperPage(context); return@Column }
                if (page == "Mixer") { MixerPage(); return@Column }
                if (page == "Effects") { EffectsPage(recorder); return@Column }
                if (page == "Recordings") { TextButton(onClick = { showRecordings = true }) { Text("Open recordings") }; return@Column }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("SCARLETT RECORDER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("USB audio interface", color = Color.Gray) }
                    val device by usbAudio.device.collectAsState()
                    AssistChip(onClick = { usbAudio.refresh() }, label = { Text(if (device == null) "No USB" else "USB ✓") }, leadingIcon = { Text("●", color = if (device == null) Color.Gray else Scarlett) })
                }
                ChannelMeter("INPUT 1", "Mono", levels.first)
                ChannelMeter("INPUT 2", "Mono", levels.second)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recording format", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Stereo", "Mono 1", "Mono 2", "Getrennt").forEach { value -> FilterChip(enabled = !recording, selected = mode == value, onClick = { mode = value }, label = { Text(value) }) } }
                        Text("48 kHz · 16 bit · WAV and MP3 export", color = Color.Gray, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = monitoring, onCheckedChange = {
                        monitoring = it
                        recorder.monitorEnabled = it
                        val intent = android.content.Intent(context, RecordingService::class.java)
                        if (it && !recording) androidx.core.content.ContextCompat.startForegroundService(context, intent.setAction("MONITOR"))
                        else if (!it && recording) context.startService(intent.setAction("STOP"))
                    })
                    Text("USB monitoring")
                }
                Text(if (audioError.isNotEmpty()) audioError else if (recording) "Recording · ${seconds}s" else if (monitoringActive) "Monitoring active" else "Ready", color = if (recording || monitoringActive) Scarlett else Color.Gray)
                Button(onClick = {
                    val intent = android.content.Intent(context, RecordingService::class.java)
                    if (recording) context.startService(intent.setAction("STOP"))
                    else runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(context, intent.putExtra("mode", mode))
                    }.onFailure { recorder.error.value = it.message ?: "Start failed" }
                }, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFF7B1D25) else Scarlett), shape = RoundedCornerShape(14.dp)) { Text(if (recording) "STOP RECORDING" else "●  START RECORDING", fontWeight = FontWeight.Bold) }
                TextButton(enabled = !recording && !monitoringActive, onClick = { showRecordings = true }) { Text("Open recordings") }
            }
        }
    }
}

@Composable private fun LooperPage(context: android.content.Context) {
    val looper = remember { LooperEngine() }
    val state by looper.state.collectAsState()
    val length by looper.loopSeconds.collectAsState()
    val progress by looper.loopProgress.collectAsState()
    DisposableEffect(looper) { onDispose { looper.stop() } }
    var bpm by remember { mutableStateOf(120f) }
    var metronomeOn by remember { mutableStateOf(false) }
    var selectedSlot by remember { mutableStateOf(0) }
    val slotStates = remember { mutableStateListOf(*Array(4) { LooperState.IDLE }) }
    val slotLengths = remember { mutableStateListOf(*Array(4) { 0f }) }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LIVE LOOPER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Tap: record/stop · double-tap: overdub · hold: delete", color = Color.Gray, fontSize = 12.sp)
            Text("BPM ${bpm.toInt()}", color = Color.White)
            Slider(value = bpm, onValueChange = { bpm = it; looper.bpm = it }, valueRange = 40f..220f)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { slot ->
                    val active = selectedSlot == slot
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .pointerInput(slot, state, length) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        selectedSlot = slot
                                        if (slotStates[slot] == LooperState.PLAYING) {
                                            looper.toggleOverdub()
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
                            .background(if (active) Color(0xFF3B2025) else Card, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                            drawArc(Color(0xFF3A3D44), -90f, 360f, false, style = Stroke(10f, cap = StrokeCap.Round))
                            val sweep = if (active) progress * 360f else if (slotLengths[slot] > 0f) 360f else 0f
                            drawArc(if (slotStates[slot] == LooperState.RECORDING) Scarlett else Color(0xFF62C978), -90f, sweep, false, style = Stroke(10f, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${slot + 1}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(slotStates[slot].name, color = Color.LightGray, fontSize = 9.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Selected loop: ${selectedSlot + 1}", color = Color.Gray)
                Text("${"%.2f".format(length)} s", color = Color.Gray)
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
            Text("State: ${state.name} · Loop length: ${"%.2f".format(length)} s", color = Color.Gray)
        }
    }
}

@Composable private fun MixerPage() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("LOOP MIXER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold); listOf("Loop 1", "Loop 2", "Loop 3", "Monitoring").forEach { name -> Row(verticalAlignment = Alignment.CenterVertically) { Text(name, Modifier.width(100.dp), color = Color.White); Slider(value = .8f, onValueChange = {}, modifier = Modifier.weight(1f)); TextButton(onClick = {}) { Text("MUTE") }; TextButton(onClick = {}) { Text("SOLO") } } } } }
}

@Composable private fun EffectsPage(recorder: AudioRecorder) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("EFFECTS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("EQ and compressor controls will be connected to the monitoring DSP in the next audio-engine pass.", color = Color.Gray); Text("Monitoring currently uses the direct low-latency USB path.", color = Color.Gray) } } }

@Composable private fun ChannelMeter(name: String, type: String, level: Float) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, color = Color.White, fontWeight = FontWeight.Bold); Text(if (level > 0f) "%.1f dBFS".format(20 * kotlin.math.log10(level)) else "−∞ dBFS", color = Color.LightGray) }
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(12.dp), color = if (level > .85f) Color.Red else Color(0xFF62C978), trackColor = Color(0xFF303238))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(type, color = Color.Gray, fontSize = 12.sp); Text("Monitoring", color = Color.Gray, fontSize = 12.sp) }
        }
    }
}
