# 📖 Kteps – Projektdokumentation

## 1. Ausgangslage

Moderne E-Bikes mit **Shimano STEPS / EP8 (DU-EP800 / EP801 / EP6)** Motoren sind häufig mit der kompakten Lenker-Verteilerbox **Shimano EW-EN100** ausgestattet. 

* **Kein ANT+ LEV:** Während viele andere E-Bike-Systeme (z. B. Specialized, Giant, Bosch) den offenen ANT+ LEV (Light Electric Vehicle) Standard nutzen, sendet die EW-EN100 Fahrdaten (Unterstützungsmodus, Akkustand, Motor-Reichweite) **ausschließlich über ein proprietäres Shimano Bluetooth Low Energy (BLE) Protokoll**.
* **Keine native Unterstützung auf dem Hammerhead Karoo 3:** Ohne zusätzliche Hardware-Bridges (wie drahtlose Display-Einheiten oder externe Übersetzer) kann der Karoo 3 das E-Bike nicht als E-Bike-Sensor erkennen oder dessen Akkustand und Unterstützungsstufen anzeigen.
* **Karoo OS Bluetooth-Besonderheit:** Der Karoo 3 nutzt ein aggressives Energiemanagement über den systemeigenen Dienst `io.hammerhead.sensorservice` (`BluetoothCoordinator`). Sind auf dem Karoo keine nativen BLE-Sensoren (wie ein Bluetooth-Herzfrequenzgurt) gekoppelt, schaltet das System den Bluetooth-Funkchip automatisch ab, um Akku zu sparen.

---

## 2. Zielsetzung

Das Ziel von **Kteps** ist die vollständige, native und drahtlose Integration von Shimano EP8 / EW-EN100 Systemen auf dem **Hammerhead Karoo 3**:

1. **Direktes Pairing:** Erkennung und Kopplung des E-Bikes direkt über das Standard-Sensormenü des Karoo (*Sensoren -> `+` -> Suche*).
2. **Grafische & Numerische E-Bike-Felder:** 
   * **EP8 Modus:** Grafische Kachel mit farbigem Statuspunkt nach offizieller Shimano-Farbcodierung (`OFF`, `ECO`, `TRAIL`, `BOOST`, `WALK`).
   * **EP8 Akku-Balken:** Dynamischer Farbbalken (Grün > 50 %, Gelb 21–50 %, Rot ≤ 20 %) mit responsiver Prozent-Plakette in Vollbreiten-Layouts.
   * **EP8 Reichweite:** Automatische Berechnung der verbleibenden Restreichweite für den aktuell aktiven Motor-Modus.
   * **EP8 Odometer & Trip:** Echte Motor-Gesamtkilometer sowie sitzungsbasierte Tageskilometer.
3. **Virtueller System-Sensor:** Gleichzeitige Einspeisung der Motordaten in die **nativen Karoo-Datenfelder** für:
   * Geschwindigkeit (`SPEED`) inkl. Karoo-Autopause und Durchschnitt
   * Trittfrequenz (`CADENCE`)
   * Fahrer-Tretleistung (`POWER` in Watt)
   * Sensor-Akkustand (`BATTERY_PERCENT`)
4. **Absolute Stabilität:** Reibungsloser Betrieb auf allen Serien-Karoos ohne Root-Rechte, Entwickler-Hacks oder manuelle ADB-Systemeingriffe.

---

## 3. Technische Lösung & Architektur

Kteps basiert auf dem offiziellen Hammerhead Extension SDK (**`karoo-ext`**) und implementiert eine saubere dreistufige Architektur:

```
┌────────────────────────────────────────────────────────┐
│                   Hammerhead Karoo OS                  │
│  (Sensor-Menü, Profile-Editor, Native Datenfelder, UI) │
└───────────────────────────▲────────────────────────────┘
                            │ IPC (karoo-ext SDK)
┌───────────────────────────┴────────────────────────────┐
│                    Kteps Extension                     │
│  (KtepsExtension.kt, RemoteViews, DataPoint Emitter)   │
└───────────────────────────▲────────────────────────────┘
                            │ Kotlin StateFlow / Telemetrie
┌───────────────────────────┴────────────────────────────┐
│                     Kteps Manager                      │
│   • Hammerhead BluetoothCoordinator Lifecycle (IPC)    │
│   • GATT Connection State Machine & Auto-Reconnect     │
│   • 10s Hybrid Battery Polling + Notification Listener │
│   • Binary Packet Parser (0x02 Realtime, 0x03 Range)   │
└───────────────────────────▲────────────────────────────┘
                            │ Bluetooth Low Energy (BLE)
┌───────────────────────────┴────────────────────────────┐
│           Shimano EW-EN100 / EP8 Drive Unit            │
└────────────────────────────────────────────────────────┘
```

### A. Reverse-Engineering der Shimano BLE-Protokolle
* **Telemetrie-Dienst (`000018ef-5348-494d-414e-4f5f424c4500`):**
  * Characteristic **`0x2AC1`**: Überträgt per GATT-Notification kontinuierlich zwei Kern-Paketarten:
    * **Paket `0x02` (Echtzeitdaten):** Unterstützungsmodus, Geschwindigkeit (0.1 km/h Auflösung), Fahrer-Watt und Trittfrequenz.
    * **Paket `0x03` (Distanz & Reichweite):** 32-Bit Gesamtkilometerstand des Motors sowie Restreichweiten in km für Boost, Trail und Eco.
  * *(Hinweis: Eine vollständige Byte-für-Byte Aufschlüsselung aller Pakete inkl. ungenutzter Di2-, Profil- und Fehler-Bytes befindet sich in der [BLE-Protokoll & Byte-Spezifikation](ble-protokoll.md)).*

### B. Hybrid-Batteriemanagement
* Shimano trennt Telemetrie und Akkustand strikt: Der Akkustand wird über den offiziellen Bluetooth SIG Dienst **Battery Service (`0x180F` / `0x2A19`)** bereitgestellt.
* Da viele EW-EN100 Firmwares keine automatischen Push-Notifications senden und der Akkustand sonst während der Fahrt einfrieren würde, nutzt Kteps eine **Hybrid-Lösung**:
  * Eintreffende Notifications werden sofort verarbeitet.
  * Ein unaufdringlicher 10-Sekunden-Timer fragt den aktuellen Akkustand per GATT-Read aktiv am Bike ab.

### C. Hammerhead `BluetoothCoordinator` Synchronisation
* Um zu verhindern, dass das Karoo-OS Bluetooth abschaltet (da keine nativen Karoo-BLE-Sensoren aktiv sind), nutzt Kteps Hammerheads interne Broadcast-Schnittstelle:
  * Beim Start von Scan oder Verbindung sendet Kteps:
    `io.hammerhead.sensorservice.BLUETOOTH_REQUEST` (Extra `ID = "kteps"`).
  * Hammerhead registriert Kteps offiziell in seiner Client-Liste und hält Bluetooth dauerhaft stabil aktiv.
  * Beim Trennen der Verbindung oder nach der Fahrt sendet Kteps:
    `io.hammerhead.sensorservice.BLUETOOTH_RELEASE` (Extra `ID = "kteps"`), sodass der Karoo wieder stromsparend schlafen kann.
