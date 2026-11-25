package de.maxgraue.scarlettrecorder

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.media.AudioManager
import android.media.AudioDeviceInfo
import de.maxgraue.scarlettrecorder.audio.AudioRecorder
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class RecordingService : Service() {
    companion object {
        val recorder = AudioRecorder()
        val active = MutableStateFlow(false)
        val recordingActive = MutableStateFlow(false)
        val monitoringActive = MutableStateFlow(false)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            recorder.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (active.value) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording", "Recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Scarlett recording active")
            .setContentText("Tap Stop to finish")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stoppen", stop).build()).build())
        try {
            val audio = getSystemService(AudioManager::class.java)
            fun usb(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { usb(it) }
            val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { usb(it) }
            val directory = getExternalFilesDir("recordings") ?: filesDir
            directory.mkdirs()
            val monitoring = intent?.action == "MONITOR"
            check(recorder.start(if (monitoring) null else File(directory, "recording-" + System.currentTimeMillis() + ".wav"), input, intent?.getStringExtra("mode") ?: "Stereo", output)) { "USB input unavailable" }
            recorder.monitorEnabled = true
            active.value = true
            monitoringActive.value = monitoring
            recordingActive.value = !monitoring
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ScarlettRecorder:Recording")
                .apply { acquire() }
            scope.launch {
                recorder.error.collect { if (it.isNotEmpty()) stopSelf() }
            }
        } catch (failure: Exception) {
            recorder.error.value = failure.message ?: "Recording failed"
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        recorder.stop()
        active.value = false
        monitoringActive.value = false
        recordingActive.value = false
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

