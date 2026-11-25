package de.maxgraue.scarlettrecorder.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UsbAudioDevice(val device: UsbDevice, val label: String, val inputChannels: Int = 2, val outputChannels: Int = 2)

class UsbAudioManager(private val context: Context) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _device = MutableStateFlow<UsbAudioDevice?>(null)
    val device: StateFlow<UsbAudioDevice?> = _device
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) { refresh() }
    }
    fun start() { ContextCompat.registerReceiver(context, receiver, IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, ContextCompat.RECEIVER_NOT_EXPORTED); refresh() }
    fun stop() { runCatching { context.unregisterReceiver(receiver) } }
    fun refresh() {
        val usbDevice = usb.deviceList.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 1 /* USB_CLASS_AUDIO */ }
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val androidUsb = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val channels = androidUsb?.channelCounts?.maxOrNull()?.coerceAtLeast(1) ?: 2
        _device.value = usbDevice?.let { UsbAudioDevice(it, it.productName ?: androidUsb?.productName?.toString() ?: "USB-Audio-Interface", channels, channels) }
    }
}
