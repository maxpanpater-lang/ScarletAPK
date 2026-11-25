# Scarlett Recorder

Android recording app for USB-C Scarlett interfaces, including the Focusrite Scarlett Solo 3rd Gen.

## Features

- USB audio detection for class-compliant interfaces
- Stereo, mono input 1, mono input 2, and separate mono files
- WAV recording with MP3 export
- Background recording with a foreground notification
- USB monitoring with EQ and compressor
- Live input meters and recording time
- Playback, sharing, and export of recordings
- Looper with loop capture, playback, overdub, clear, BPM, and count-in controls
- Separate Recorder, Looper, Mixer, Effects, and Recordings pages

## MP3 export

The original recording is always stored as lossless WAV. MP3 is created during export with the bundled Jump3r encoder. WAV remains available if an export fails.

## Audio routing

The app selects the connected USB input and output through Android's audio device routing. The actual channel mapping and monitoring latency must be verified with the connected Scarlett hardware.

## Development

The project uses Android Studio, Gradle, Kotlin, and Jetpack Compose. GitHub Actions builds a debug APK from the `main` branch.

## Build

```bash
./gradlew :app:assembleDebug
```

The generated APK is located at `app/build/outputs/apk/debug/app-debug.apk`.

