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

Die Originalaufnahme wird immer verlustfrei als WAV gespeichert. MP3 wird erst beim Export erzeugt. Der Export nutzt `MediaCodec` mit `audio/mpeg` und schreibt die erzeugten MP3-Frames direkt in die Datei. Da dieser Encoder nicht auf jedem Android-Gerät angeboten wird, bleibt WAV immer verfügbar und der Export liefert bei fehlender Unterstützung einen verständlichen Fehler.

## Nächste technische Schritte

1. USB-Geräteliste und Permission-Flow
2. echte Kanalparameter aus dem Scarlett auslesen
3. AudioRecord/Oboe-Aufnahmepipeline
4. WAV-Writer und Pegelmessung
5. MP3-Exportpipeline mit 128/192/256/320 kbps
6. Tests mit Scarlett Solo, 2i2 und 4i4

## Entwicklung

Das Projekt ist für Android Studio und Gradle eingerichtet. Das Repository sollte zunächst privat bleiben, bis die erste Hardware-Testversion funktioniert.

