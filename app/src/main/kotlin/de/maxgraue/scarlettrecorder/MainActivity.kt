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
    val recording by RecordingService.active.collectAsState()
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
            title = { Text("Aufnahmen") },
            text = {
                LazyColumn {
                    item { Text(if (exporting) "MP3 wird exportiert …" else exportMessage) }
                    items(files) { file ->
                        TextButton(onClick = {
                            runCatching {
                                player.reset()
                                player.setDataSource(file.absolutePath)
                                player.prepare()
                                player.start()
                            }.onFailure { recorder.error.value = "Wiedergabe fehlgeschlagen" }
                        }) { Text(file.name + " · " + file.length() / 1024 + " KB") }
                        TextButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType(if (file.extension == "mp3") "audio/mpeg" else "audio/wav")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(share, "Aufnahme teilen"))
                        }) { Text("Teilen") }
                        if (file.extension == "wav") TextButton(enabled = !exporting, onClick = {
                            exporting = true
                            exportScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    Mp3Exporter.export(file, File(file.parentFile, file.nameWithoutExtension + "-${System.currentTimeMillis()}.mp3"))
                                }
                                exportMessage = result.fold({ "MP3 gespeichert · 192 kbit/s" }, { "MP3-Export fehlgeschlagen: ${it.message}" })
                                revision++
                                exporting = false
                            }
                        }) { Text("Als MP3 exportieren") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { player.reset(); showRecordings = false }) { Text("Schließen") } })
    }
    val levels by recorder.levels.collectAsState()
    val seconds by recorder.elapsedSeconds.collectAsState()
    val audioError by recorder.error.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme(primary = Scarlett, background = Bg, surface = Card)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("SCARLETT RECORDER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("USB-Audio-Aufnahme", color = Color.Gray) }
                    val device by usbAudio.device.collectAsState()
                    AssistChip(onClick = { usbAudio.refresh() }, label = { Text(if (device == null) "Kein USB" else "USB ✓") }, leadingIcon = { Text("●", color = if (device == null) Color.Gray else Scarlett) })
                }
                ChannelMeter("INPUT 1", "Mono", levels.first)
                ChannelMeter("INPUT 2", "Mono", levels.second)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Aufnahmeformat", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Stereo", "Mono 1", "Mono 2", "Getrennt").forEach { value -> FilterChip(enabled = !recording, selected = mode == value, onClick = { mode = value }, label = { Text(value) }) } }
                        Text("48 kHz · 16 Bit · WAV", color = Color.Gray, fontSize = 12.sp)
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
                    Text("Software-Monitoring über USB")
                }
                Text("EQ " + recorder.effects.settings.eq + " · Compressor " + recorder.effects.settings.compressor, color = Color.Gray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { recorder.effects.settings.eq = (recorder.effects.settings.eq + 0.1f).coerceAtMost(1f) }) { Text("EQ +") }
                    TextButton(onClick = { recorder.effects.settings.compressor = (recorder.effects.settings.compressor + 0.1f).coerceAtMost(0.9f) }) { Text("Comp +") }
                }
                Text(if (audioError.isNotEmpty()) audioError else if (recording) "Aufnahme · ${seconds}s" else "Bereit für Aufnahme", color = if (recording) Scarlett else Color.Gray)
                Button(onClick = {
                    val intent = android.content.Intent(context, RecordingService::class.java)
                    if (recording) context.startService(intent.setAction("STOP"))
                    else runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(context, intent.putExtra("mode", mode))
                    }.onFailure { recorder.error.value = it.message ?: "Start fehlgeschlagen" }
                }, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFF7B1D25) else Scarlett), shape = RoundedCornerShape(14.dp)) { Text(if (recording) "AUFNAHME STOPPEN" else "●  AUFNAHME STARTEN", fontWeight = FontWeight.Bold) }
                TextButton(enabled = !recording, onClick = { showRecordings = true }) { Text("Aufnahmen abspielen") }
            }
        }
    }
}

@Composable private fun ChannelMeter(name: String, type: String, level: Float) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, color = Color.White, fontWeight = FontWeight.Bold); Text(if (level > 0f) "%.1f dBFS".format(20 * kotlin.math.log10(level)) else "−∞ dBFS", color = Color.LightGray) }
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(12.dp), color = if (level > .85f) Color.Red else Color(0xFF62C978), trackColor = Color(0xFF303238))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(type, color = Color.Gray, fontSize = 12.sp); Text("Monitoring aus", color = Color.Gray, fontSize = 12.sp) }
        }
    }
}

