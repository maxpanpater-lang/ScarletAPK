# Scarlett Recorder

Android-Aufnahme-App für USB-C-Scarlett-Interfaces.

## Geplant / aktueller Stand

- USB-Audio-Erkennung für class-compliant Interfaces
- Stereo- und Mono-Aufnahme
- WAV als natives Aufnahmeformat
- MP3-Export nach der Aufnahme über Androids `audio/mpeg`-Encoder, mit sauberem WAV-Fallback
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
