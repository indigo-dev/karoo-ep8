# 📦 Installationsanleitung (Schritt für Schritt)

Diese Anleitung führt dich durch die erstmalige Einrichtung und das Sideloading von **Kteps** auf deinem **Hammerhead Karoo** (Karoo 2 & Karoo 3).

---

## Schritt 1: Sideloading & Entwickleroptionen auf dem Karoo aktivieren

Da Kteps eine Drittanbieter-Erweiterung ist, muss auf dem Karoo einmalig das sogenannte **USB-Debugging** aktiviert werden:

1. **Einstellungen öffnen:**
   * Wische auf deinem Karoo vom oberen Bildschirmrand nach unten und tippe auf das **Zahnrad-Symbol** (Einstellungen).
2. **Entwickleroptionen freischalten:**
   * Scrolle ganz nach unten zu **Über das Gerät** (oder **Info**).
   * Suche den Eintrag **Build-Nummer**.
   * Tippe **7-mal schnell hintereinander** auf die **Build-Nummer**.
   * Auf dem Bildschirm erscheint der Hinweis: *„Du bist jetzt ein Entwickler!“*.
3. **USB-Debugging einschalten:**
   * Gehe einen Schritt zurück in die Einstellungen und öffne das Menü **System** -> **Entwickleroptionen** (Developer options).
   * Suche den Schalter **USB-Debugging** (Android Debug Bridge) und schalte ihn **EIN**.
   * Bestätige den Sicherheitsdialog mit **OK**.

---

## Schritt 2: Kteps APK auf den Karoo übertragen (Sideload)

Lade dir die neueste `kteps.apk` von der [GitHub Release Seite](https://github.com/indigo-dev/kteps/releases) auf deinen PC herunter.

### Option A: Installation per ADB (Empfohlen)

1. Verbinde deinen Karoo per **USB-C Kabel** mit deinem PC oder Mac.
2. Wenn auf dem Karoo-Bildschirm die Abfrage *„USB-Debugging zulassen?“* erscheint:
   * Setze einen Haken bei *„Von diesem Computer immer zulassen“* und tippe auf **Zulassen**.
3. Öffne auf deinem Computer ein Terminal (Eingabeaufforderung oder PowerShell):
   ```bash
   adb install -r kteps.apk
   ```
4. Nach wenigen Sekunden erscheint die Meldung **`Success`**.

*(Hinweis: Falls du noch kein ADB auf deinem PC installiert hast, kannst du die [Google Platform-Tools](https://developer.android.com/tools/releases/platform-tools) herunterladen oder ein browserbasiertes Sideloading-Tool wie WebADB nutzen).*

### Option B: Installation über die Hammerhead Companion App

Falls du ein Android-Smartphone mit der Hammerhead Companion App nutzt, kannst du heruntergeladene APK-Dateien teilweise auch direkt über die App an den Karoo senden (sofern dein Karoo mit dem Smartphone gekoppelt ist).

---

## Schritt 3: Berechtigungen einmalig erteilen

Android verlangt für Bluetooth Low Energy (BLE) Scans Berechtigungen für Bluetooth und Standort:

1. Drücke die Taste für das **Hauptmenü / App-Drawer** auf deinem Karoo (Raster mit den installierten Apps).
2. Suche die App **Kteps** und tippe darauf, um sie einmalig zu öffnen.
3. Bestätige die angefragten Berechtigungen (**Bluetooth** und ggf. **Standort**), bis im Statusbildschirm alle Punkte grün markiert sind.
4. Du kannst die App anschließend wieder verlassen – sie läuft ab sofort automatisch im Hintergrund als Karoo-Erweiterung.

---

## Schritt 4: E-Bike als Sensor koppeln

1. **E-Bike einschalten:**
   * Schalte dein Shimano E-Bike am Haupttaster ein. Die LED an der EW-EN100 blinkt kurz blau auf und signalisiert Bluetooth-Empfangsbereitschaft.
2. **Sensorsuche am Karoo starten:**
   * Gehe auf dem Karoo in das Menü **Sensoren**.
   * Tippe auf das **`+` (Hinzufügen)** Symbol und wähle **Suche**.
3. **Sensor auswählen:**
   * Nach wenigen Sekunden erscheint dein Bike in der Liste (z. B. als `EWEN100 0AB` oder `Shimano STEPS`).
   * Tippe auf das Bike und wähle **Sensor hinzufügen** (oder bestätige mit dem Haken).
4. Der Sensor erscheint nun in deiner Sensor-Liste mit einem Erweiterungssymbol (Puzzleteil) und verbindet sich automatisch.

---

## Schritt 5: Datenfelder zum Fahrprofil hinzufügen

Jetzt kannst du die neuen E-Bike-Datenfelder in dein gewünschtes Karoo-Fahrprofil einbinden:

1. Gehe im Karoo auf **Profile** und wähle dein Fahrprofil aus.
2. Wähle **Seiten bearbeiten** und tippe auf die Seite, die du anpassen möchtest.
3. Tippe auf ein Datenfeld und wähle unter den Kategorien:
   * **Kategorie „Kteps E-Bike“:**
     * **EP8 Modus:** Grafische Kachel mit farbigem Statuspunkt (`● OFF`, `ECO`, `TRAIL`, `BOOST`, `WALK`).
     * **EP8 Akku Balken:** Grafischer, dreifarbiger Ladebalken. In Feldern voller Bildschirmbreite wird zusätzlich eine Prozent-Plakette angezeigt.
     * **EP8 Reichweite:** Dynamische Restreichweite in km passend zum aktiven Fahrmodus.
     * **EP8 Akku:** Große numerische Prozentanzeige (`0–100 %`).
     * **EP8 Trip-km / Gesamt-km:** Tageskilometer der aktuellen Fahrt und Gesamtkilometerstand des Motors.
   * **Native Standardfelder des Karoo:**
     * Du kannst auch ganz normale Standard-Datenfelder für **Geschwindigkeit**, **Trittfrequenz**, **Leistung (Watt)** und **Sensor-Batterie** belegen – Kteps füttert diese automatisch mit den Motordaten.
