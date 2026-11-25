# Scarlett Recorder

Android recording app for USB-C Scarlett interfaces.

## Geplant / aktueller Stand

- USB audio detection for class-compliant interfaces
- Stereo and mono recording
- WAV as the lossless recording format
- MP3 export with a bundled encoder
- latenzarmes Monitoring mit Direct-Monitoring-Hinweis und Software-Monitoring
- dunkle Compose-Oberfläche mit Scarlett-Rot

## MP3-Export

The original recording is always stored as lossless WAV. MP3 is created during export with the bundled encoder. WAV remains available if an export fails.

## Nächste technische Schritte

1. USB-Geräteliste und Permission-Flow
2. echte Kanalparameter aus dem Scarlett auslesen
3. AudioRecord/Oboe recording pipeline
4. WAV-Writer und Pegelmessung
5. MP3-Exportpipeline mit 128/192/256/320 kbps
6. Tests mit Scarlett Solo, 2i2 und 4i4

## Entwicklung

Das Projekt ist für Android Studio und Gradle eingerichtet. Das Repository sollte zunächst privat bleiben, bis die erste Hardware-Testversion funktioniert.

