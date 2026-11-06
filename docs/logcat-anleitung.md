# 🛠️ Fehleranalyse: Logcat-Aufzeichnung für Support

Falls Kteps auf deinem Karoo unerwartet hängenbleibt (z. B. dauerhafter Status `SUCHE...` / `SEARCHING`, keine Datenanzeige oder Verbindungsabbrüche), ist ein **Android Logcat** das wichtigste Werkzeug. Damit kann der Entwickler den genauen Funk-Handshake, Bluetooth-Fehlercodes und interne Systemmeldungen sekundengenau nachvollziehen.

---

## 1. Wichtige Vorbereitung vor der Aufzeichnung

Bevor du mit dem Mitschnitt beginnst, beachte bitte folgende Punkte:

### A. Warum muss der Karoo vorher neu gestartet werden?
* Android speichert Systemprotokolle in einem rotierenden Ringspeicher (Ring-Buffer). Wenn der Karoo tagelang lief, enthält dieser Puffer Hunderttausende Zeilen alter Ereignisse.
* Ein **Kaltstart (Neustart)** leert alle temporären Speicher und garantiert eine saubere, übersichtliche Ausgangslage ohne Altlasten.

### B. Soll der Sensor vorher entfernt werden?
* **Ja, unbedingt!**
* Gehe vor dem Test auf dem Karoo in **Sensoren**, tippe auf deinen Shimano-Sensor (`EWEN100...`) und wähle **Löschen / Entfernen**.
* **Grund:** Nur wenn der Sensor neu hinzugefügt wird, können wir im Log den vollständigen Ablauf von der ersten Funk-Erkennung über den Bluetooth-Sicherheits-Handshake bis zum Start des Telemetrie-Streams einsehen.

### C. Soll Kteps vorher neu installiert werden?
* Überprüfe, ob du die **aktuellste Version** installiert hast (z. B. unter *Über das Gerät -> Apps -> Kteps* oder durch Öffnen der Kteps-App).
* Falls du von einer älteren Version aktualisiert hast, schadet es nicht, die App einmal zu deinstallieren (`adb uninstall io.github.indigodev.kteps`) und die neueste APK frisch zu installieren.

---

## 2. Schritt-für-Schritt-Anleitung zur Aufzeichnung

### Schritt 1: Karoo vorbereiten
1. Schalte dein **E-Bike zunächst AUS**.
2. Entferne den Shimano-Sensor aus dem Karoo Sensormenü (falls noch vorhanden).
3. **Starte den Karoo neu** (Ein-/Ausschalttaste gedrückt halten -> *Neu starten*).
4. Warte, bis der Karoo vollständig hochgefahren ist.

### Schritt 2: Mit dem PC verbinden & ADB prüfen
1. Verbinde den Karoo per **USB-C Kabel** mit deinem Computer.
2. Öffne auf deinem PC eine **Eingabeaufforderung (CMD)** oder **PowerShell**.
3. Prüfe, ob das Gerät erkannt wird:
   ```bash
   adb devices
   ```
   *Es sollte eine Gerätenummer mit dem Status `device` erscheinen (z. B. `00446GA250720019   device`). Falls dort `unauthorized` steht, bestätige bitte den Dialog auf dem Karoo-Bildschirm.*

### Schritt 3: Alten Protokollpuffer leeren
Führe folgenden Befehl aus, um veraltete Protokolle vor dem Testbeginn zu verwerfen:
```bash
adb logcat -c
```

### Schritt 4: Live-Aufzeichnung in Datei starten
Starte jetzt die kontinuierliche Protokollierung in eine Textdatei:
```bash
adb logcat -v time > karoo_kteps_debug.log
```
*(Das Terminal bleibt nun aktiv stehen und wartet auf Eingaben – schließe das Fenster nicht).*

### Schritt 5: Das Problem am Fahrrad reproduzieren
Führe nun am Karoo und Bike die Schritte aus, bei denen der Fehler auftritt:
1. Schalte dein **Shimano E-Bike ein** (Haupttaster am Akku oder Rahmen).
2. Öffne auf dem Karoo das Menü **Sensoren**.
3. Tippe auf das **`+` (Hinzufügen)** -> **Suche**.
4. Warte, bis das Bike gefunden wird, und tippe auf **Hinzufügen**.
5. Verlasse den Suchbildschirm und beobachte den Sensor in der Liste für ca. **30 bis 60 Sekunden** (ob er von `SUCHE...` auf `VERBUNDEN` wechselt oder hängen bleibt).
6. Schalte bei Bedarf einmal die Unterstützungsstufe am Lenker um (z. B. von Eco auf Trail).

### Schritt 6: Aufzeichnung beenden & Datei einsenden
1. Gehe zurück an deinen Computer in das Terminalfenster.
2. Drücke die Tastenkombination **`Strg + C`** (auf Mac: `Cmd + C`), um die Aufzeichnung zu beenden.
3. Im aktuellen Ordner befindet sich nun die Datei **`karoo_kteps_debug.log`**.
4. Hänge diese Datei an ein [GitHub Issue](https://github.com/indigo-dev/kteps/issues) an oder sende sie an den Entwickler.

---

## 3. Was der Entwickler im Log überprüft

Anhand dieser Datei kann innerhalb von Sekunden analysiert werden:
* **`BluetoothCoordinator` Status:** Meldet Hammerhead `clients=[kteps]`, oder schaltet ein Systemprozess den Funkchip ab?
* **GATT Handshake:** Konnten die Characteristics `0x2AC1` (Telemetrie) und `0x2A19` (Batterie) erfolgreich abonniert werden?
* **Fehlercodes:** Werden Statuscodes wie `133` (Verbindungs-Timeout) oder `255` (Generic Error) vom Shimano-Controller zurückgegeben?
* **Paketempfang:** Treffen die binären `0x02`- und `0x03`-Datenpakete sauber im Intervall ein?
