# 📡 Shimano STEPS BLE-Protokoll & Byte-Spezifikation

Dieses Dokument beschreibt das proprietäre Bluetooth Low Energy (BLE) Kommunikationsprotokoll von Shimano STEPS / EP8 E-Bikes (EW-EN100, SC-EM800, SC-E7000, SC-EN600 etc.) und schlüsselt die genaue Byte-Belegung der Telemetrie-Pakete auf.

---

## 1. Bluetooth Low Energy Architektur

Shimano nutzt für E-Bikes keine Standard-GATT-Fahrradprofile (wie Cycling Speed & Cadence `0x1816` oder Cycling Power `0x1818`), sondern wickelt die Live-Telemetrie über eigene herstellerspezifische Dienste ab.

### Base UUID
Die Shimano Base-UUID lautet:
```text
0000xxxx-5348-494d-414e-4f5f424c4500
```
*(Die Hex-Folge `53 48 49 4D 41 4E 4F 5F 42 4C 45 00` entspricht ASCII: `SHIMANO_BLE\0`)*

### GATT-Dienste & Characteristics

| Service | UUID | Characteristic | UUID | Eigenschaften | Beschreibung |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **D-Fly Telemetrie** | `000018ef-...` | **Telemetrie Stream** | `00002ac1-...` | `Notify` | Zentraler Live-Fahrdatenstrom (Modus, Speed, Watt, Trittfrequenz, Odo, Reichweite) |
| | | **D-Fly Switches** | `00002ac2-...` | `Indicate` | Tastendrücke von Di2-Zusatzknöpfen (CH1–CH4) |
| | | Handshake / Control | `00002ac0-...` / `2ac3` / `2ac4` | `Indicate`, `Read`, `Write` | Verbindungs- und Statuskanäle |
| **Battery Service** | `0000180f-...` | **Battery Level** | `00002a19-...` | `Notify`, `Read` | E-Bike-Hauptakku in Prozent (0–100 %) |
| **Device Info** | `000018fe-...` | **Hardware MAC** | `00002ae2-...` / `2ae3` | `Read` | Statische MAC-Adresse des Funkmoduls |
| **Konfiguration** | `000018ff-...` | *Diverse* | *Diverse* | `Read`, `Write` | E-Tube Project (Firmware-Updates, Tuning). *Nicht für Live-Fahrdaten!* |

---

## 2. Hardware-Kompatibilität & Besonderheiten

* **EW-EN100, SC-EM800, SC-E7000, SC-EN600, SC-E6100:**  
  Stellen alle den Telemetrie-Dienst `0x18EF` bereit und sind mit dem Kteps-Parser zu 100 % kompatibel.
* **SC-E8000:**  
  ❌ **Inkompatibel.** Bietet nur den Konfigurationsdienst `0x18FF` (für E-Tube Project), verfügt aber über keinen `0x18EF` Live-Telemetrie-Dienst.
* **SC-E5000:**  
  ❌ **Inkompatibel.** Besitzt keine Bluetooth-Hardware.
* **Bonding & Passkey:**  
  Während das EW-EN100 ohne Display direkt koppelt, verlangen Displays wie das SC-EM800 oder SC-E7000 beim ersten Verbindungsaufbau meist die Eingabe eines 6-stelligen Passkeys auf dem Karoo, bevor Notifications abonniert werden können.

---

## 3. Byte-Struktur der Telemetrie (`Characteristic 0x2AC1`)

Über die Characteristic `00002ac1-5348-494d-414e-4f5f424c4500` sendet das Bike zyklische Notifications. Das **erste Byte (`bytes[0]`)** dient als Paket-Identifikator.

Alle Mehr-Byte-Zahlenwerte werden im **Little-Endian-Format** übertragen.  
Ungültige bzw. nicht unterstützte Werte werden als Maximalwerte signalisiert:
* `0xFF` (UInt8, 255)
* `0xFFFF` (UInt16, 65535)
* `0xFFFFFFFF` (UInt32, 4294967295)

---

### A. Paket `0x02`: Echtzeit-Telemetrie (10 Bytes)

Wird kontinuierlich mit hoher Frequenz (ca. 4–10 Hz) gesendet.

| Byte-Index | Datentyp | Bezeichnung | Status in Kteps | Beschreibung & Umrechnung |
| :---: | :---: | :--- | :---: | :--- |
| `0` | UInt8 | **Packet ID** | ✅ Genutzt | Fester Wert `0x02` |
| `1` | UInt8 | **Assist Mode** | ✅ Genutzt | Unterstützungsstufe:<br>`0` = OFF<br>`1` = ECO<br>`2` = TRAIL<br>`3` = BOOST<br>`4` = WALK |
| `2..3` | UInt16 LE | **Speed** | ✅ Genutzt | Aktuelle Geschwindigkeit in 0,1 km/h Schritten.<br>`Wert / 10.0` (z. B. `254` = 25,4 km/h) |
| `4` | UInt8 | **Rider Power / Assist** | ✅ Genutzt | Fahrer-Tretleistung in Watt (bzw. bei manchen Firmware-Versionen relative Motorunterstützung in %) |
| `5` | UInt8 | **Cadence** | ✅ Genutzt | Trittfrequenz in Kurbelumdrehungen pro Minute (RPM) |
| `6` | UInt8 | **Di2 Rear Gear** | ⚪ *Ungenutzt* | Eingelegter Gang hinten (z. B. 1–11 / 1–12 bei Shimano Di2) |
| `7` | UInt8 | **Di2 Front Gear** | ⚪ *Ungenutzt* | Kettenblatt vorne bzw. Gesamtzahl der Gänge |
| `8` | UInt8 | **Status Flags** | ⚪ *Ungenutzt* | Bitmaske für Systemzustände:<br>• **Bit 0:** Aktives Fahrprofil (`0` = Profil 1, `1` = Profil 2)<br>• **Bit 1:** Lichtstatus (0 = Aus, 1 = Ein)<br>• **Bit 2:** Schiebehilfe aktiv |
| `9` | UInt8 | **Assist Dynamics** | ⚪ *Ungenutzt* | Dynamische Drehmoment- / Assistenz-Statusflags |

---

### B. Paket `0x03`: Distanz & Reichweite (19 Bytes)

Wird ca. alle 1–2 Sekunden gesendet.

| Byte-Index | Datentyp | Bezeichnung | Status in Kteps | Beschreibung & Umrechnung |
| :---: | :---: | :--- | :---: | :--- |
| `0` | UInt8 | **Packet ID** | ✅ Genutzt | Fester Wert `0x03` |
| `1..4` | UInt32 LE | **Odometer** | ✅ Genutzt | Gesamtlaufleistung des Motors in Metern |
| `5..8` | UInt32 LE | **Trip Distance** | ⚪ *Ungenutzt* | Interner Tageskilometerzähler des Shimano-Bikes in Metern *(Kteps berechnet den Trip relativ ab Verbindungsstart)* |
| `9..10` | UInt16 LE | **Trip Time** | ⚪ *Ungenutzt* | Fahrzeit des aktuellen Trips in Sekunden |
| `11..12` | UInt16 LE | **Range Reserve** | ⚪ *Ungenutzt* | Durchschnitts-/Gesamtreichweite bzw. Reserve-Slot |
| `13..14` | UInt16 LE | **Range Boost** | ✅ Genutzt | Geschätzte Restreichweite für Boost in km |
| `15..16` | UInt16 LE | **Range Trail** | ✅ Genutzt | Geschätzte Restreichweite für Trail in km |
| `17..18` | UInt16 LE | **Range Eco** | ✅ Genutzt | Geschätzte Restreichweite für Eco in km |

---

### C. Paket `0x01`: Systemstatus & Diagnose (ca. 15–17 Bytes)

Wird ereignisbasiert bei Statusänderungen oder Störungen übertragen.

| Byte-Index | Datentyp | Bezeichnung | Status in Kteps | Beschreibung & Umrechnung |
| :---: | :---: | :--- | :---: | :--- |
| `0` | UInt8 | **Packet ID** | ⚪ *Ungenutzt* | Fester Wert `0x01` |
| `1..4` | UInt32 | **Error / Warning Codes** | ⚪ *Ungenutzt* | Aktive System-Fehlercodes (z. B. `W013`, `E010`). `0` wenn keine Störung vorliegt |
| `5` | UInt8 | **Di2 Rear Gear** | ⚪ *Ungenutzt* | Redundante Ganganzeige für Schaltung |
| `6` | UInt8 | **Di2 Front Gear** | ⚪ *Ungenutzt* | Redundante Kettenblattanzeige |
| `7..8` | UInt16 | **Di2 Shifting Mode** | ⚪ *Ungenutzt* | Schaltmodus (Manuell, Semi-Synchro, Full-Synchro) |
| `9..13` | - | **Bus / Device States** | ⚪ *Ungenutzt* | Status der angeschlossenen Komponenten auf dem E-Tube-Bus |
| `14` | UInt8 | **Walk Button** | ⚪ *Ungenutzt* | Taster Schiebehilfe gedrückt (`1`) / losgelassen (`0`) |

---

## 4. Akkustand (Battery Service `0x180F` / `0x2A19`)

* **Paketformat:** 1 Byte (UInt8, Wertebereich `0..100` %).
* **Besonderheit:** Viele Shimano Funkmodule (insbesondere ältere EW-EN100 Firmwares) senden den Akkustand nicht verlässlich per Push-Notification.
* **Lösung in Kteps:** Hybrid-Betrieb aus sofortiger Verarbeitung eintreffender Notifications und einem aktiven GATT-Read alle 10 Sekunden.
