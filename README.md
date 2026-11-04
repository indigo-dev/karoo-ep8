# 🚴‍♂️ Shimano EP8 / EW-EN100 Extension for Hammerhead Karoo 3

A native **Karoo Extension (`karoo-ext`)** for the **Hammerhead Karoo 3** that connects Shimano STEPS e-bike systems (e.g., **Shimano EP8 / EP800**) directly via the **Shimano EW-EN100** Bluetooth LE interface—no external hardware bridges required.

Because the EW-EN100 broadcasts e-bike telemetry (assist mode, battery state, remaining range, odometer) over a proprietary Shimano BLE protocol rather than open ANT+ LEV, this extension decodes the raw BLE notifications in real time. It provides **custom graphical and numeric e-bike data fields** while simultaneously feeding **speed, cadence, rider power (Watts), and battery level** into the **native Karoo system data fields** as a virtual sensor.

---

## ✨ Features

* **Direct BLE Pairing in the Karoo Sensor Menu:** Discovers Shimano EW-EN100 / STEPS units directly under *Sensors -> `+` -> Search*.
* **Automatic BLE Reconnection & Live Battery Monitoring:** Automatically reconnects after mid-ride power-offs without resetting session trip distance, and tracks battery percentage via GATT notifications + periodic fallback polling.
* **Virtual Sensor for Native Karoo Data Fields:**
  * Feeds native Karoo data fields for **Speed** (`SPEED`, including average, max, and auto-pause), **Cadence** (`CADENCE`), **Rider Power** (`POWER`, including 3s/10s smoothed watts, Normalized Power, and zones), and **Sensor Battery** (`BATTERY_PERCENT`).
* **Graphical Assist Mode Tile (`EP8 Mode` / `EP8 Modus`):**
  * Clear text indicator paired with a colored status dot matching the official Shimano color scheme:
    * ⚫ **OFF** (Gray) – Motor assistance off
    * 🔵 **ECO** (Blue) – Eco mode
    * 🟢 **TRAIL** (Green) – Trail mode
    * 🟠 **BOOST** (Yellow/Orange) – Boost mode
    * 🩵 **WALK** (Cyan) – Walk assist selected (Standby)
    * 🟣 **WALK+** (Purple) – Walk assist active (Motor pushing)
* **Graphical Battery Bar (`EP8 Battery Bar` / `EP8 Akku Balken`):**
  * Visual progress bar styled to match native Karoo graphics with automatic color thresholds (**Green** > 50%, **Yellow** 21–50%, **Red** ≤ 20%).
  * **Responsive Percentage Badge:** When placed in a full-width data field (`gridSize == 60`), a dark percentage badge (e.g., `62 %`) automatically appears in the bottom-left corner of the bar, replicating the native Hammerhead e-bike battery field.
* **Dynamic Remaining Range (`EP8 Range` / `EP8 Reichweite`):**
  * A single, unified data field that automatically displays the remaining range in kilometers matching the currently active assist mode (`ECO`, `TRAIL`, or `BOOST`).
* **True Motor Odometer & Session Trip Counter:**
  * Decodes the internal total distance (odometer) of the EP8 motor down to the meter and calculates the current session trip distance since connection.
* **Localized (English & German):** Automatically displays English or German data field names based on Karoo system language.

---

## 📊 Available Data Fields

Once your e-bike is paired, the following data fields are available in the Karoo 3 profile editor:

### 1. Custom Extension Data Fields (Category: *Shimano EP8 E-Bike*)

| Data Field (EN / DE) | Type | Unit / Format | Description |
| :--- | :---: | :---: | :--- |
| **EP8 Mode** / **EP8 Modus** | Graphical | `● OFF` / `ECO` / `TRAIL` / `BOOST` / `WALK` / `WALK+` | Current assist mode with Shimano color-coded status dot |
| **EP8 Range** / **EP8 Reichweite** | Numeric | `km` | Remaining range for the currently active assist mode |
| **EP8 Battery** / **EP8 Akku** | Numeric | `%` | Battery state of charge as a large numeric percentage (`0–100`) |
| **EP8 Battery Bar** / **EP8 Akku Balken** | Graphical | Progress Bar (+ `%` on full width) | Color-changing battery bar (Green/Yellow/Red) with percentage badge in 1-column full-width layouts |
| **EP8 Power** / **EP8 Leistung** | Numeric | `W` | Current rider pedal power in Watts |
| **EP8 Cadence** / **EP8 Kadenz** | Numeric | `RPM` | Current pedaling cadence |
| **EP8 Speed** / **EP8 Tempo** | Numeric | `km/h` | Current e-bike speed from the rear wheel sensor |
| **EP8 Trip** / **EP8 Trip-km** | Numeric | `km` | Trip distance ridden since connecting |
| **EP8 Odometer** / **EP8 Gesamt-km** | Numeric | `km` | Total motor odometer distance |

### 2. Supported Native Karoo System Fields

Because the extension registers as a multi-type sensor within Karoo OS, you can also use standard Hammerhead data fields alongside the custom ones:
* **Power:** Power, 3s Power, 10s Power, Average Power, Normalized Power (NP), Power Zones.
* **Cadence:** Cadence, Average Cadence, Cadence Zones.
* **Speed:** Speed, Average Speed, Max Speed.
* **Battery:** Sensor Battery Percentage.

---

## 🔬 Decoded Shimano BLE Protocol (EW-EN100)

Communication uses Bluetooth Low Energy (GATT) via the standard Battery Service and the proprietary Shimano D-Fly / Telemetry characteristic.

### GATT Services & Characteristics
* **Battery Service:** `0000180f-0000-1000-8000-00805f9b34fb`
  * **Battery Level Characteristic:** `00002a19-0000-1000-8000-00805f9b34fb` (Read & Notify)
* **Shimano Auth / Discovery Service:** `000018ff-5348-494d-414e-4f5f424c4500`
* **Shimano D-Fly / Telemetry Service:** `000018ef-5348-494d-414e-4f5f424c4500`
  * **Telemetry Characteristic:** `00002ac1-5348-494d-414e-4f5f424c4500` (Notify via CCCD `0x2902`)

*(Note: The ASCII bytes `53-48-49-4D-41-4E-4F-5F-42-4C-45` inside the Shimano UUIDs spell out `"SHIMANO_BLE"`).*

### Packet Structure on `00002ac1-5348-494d-414e-4f5f424c4500`

| Metric | Source / Packet Header | Byte Offset | Data Type & Conversion | Description / States |
| :--- | :--- | :---: | :--- | :--- |
| **Battery Level (%)** | `0x2A19` (Battery) | `bytes[0]` | `UInt8` (1:1 in `%`) | `0` to `100 %` |
| **Assist Mode** | `0x2AC1` -> Packet `0x02` (10 Bytes) | `bytes[1]` | `UInt8` | `0`=OFF, `1`=ECO, `2`=TRAIL, `3`=BOOST, `4`=WALK (Standby), `5`=WALK+ (Active pushing) |
| **Speed** | `0x2AC1` -> Packet `0x02` (10 Bytes) | `bytes[2..3]` | `UInt16 LE` / `10.0` | Speed in `km/h` (`0xFFFF` = Invalid / Stationary) |
| **Rider Power** | `0x2AC1` -> Packet `0x02` (10 Bytes) | `bytes[4]` | `UInt8` | Rider pedal input in `Watts` (`0` when coasting) |
| **Cadence** | `0x2AC1` -> Packet `0x02` (10 Bytes) | `bytes[5]` | `UInt8` | Pedaling cadence in `RPM` |
| **Walk Button State** | `0x2AC1` -> Packet `0x01` (15 Bytes) | `bytes[14]` | `UInt8` | `0x01` = Released, `0x00` = Walk button held down |
| **Total Odometer** | `0x2AC1` -> Packet `0x03` (19 Bytes) | `bytes[1..4]` | `UInt32 LE` | Total motor odometer in **meters** (divided by `1000.0` = `km`) |
| **Range BOOST** | `0x2AC1` -> Packet `0x03` (19 Bytes) | `bytes[13..14]`| `UInt16 LE` | Estimated remaining range in BOOST mode in `km` |
| **Range TRAIL** | `0x2AC1` -> Packet `0x03` (19 Bytes) | `bytes[15..16]`| `UInt16 LE` | Estimated remaining range in TRAIL mode in `km` |
| **Range ECO** | `0x2AC1` -> Packet `0x03` (19 Bytes) | `bytes[17..18]`| `UInt16 LE` | Estimated remaining range in ECO mode in `km` |

---

## 🛠️ Installation & Setup on Karoo 3

1. **Download the APK:**
   * Download the latest `karoo-ep8.apk` from the **GitHub Actions Artifacts** (or the *Releases* section).
2. **Sideload via ADB or Companion App:**
   * Enable Developer Options and *USB Debugging* on your Karoo 3.
   * *(Note: If upgrading from an older pre-1.1 build using the template package ID, uninstall it once first via `adb uninstall io.hammerhead.karooexttemplate`).*
   * Install the APK from your computer using ADB:
     ```bash
     adb install -r karoo-ep8.apk
     ```
   * *(Alternatively, sideload the APK using the Hammerhead Companion App on your smartphone).*
3. **Grant Permissions Once:**
   * Open the **“Shimano EP8”** app once from the Karoo app drawer and grant the **Location and Bluetooth permissions** (required by Android for BLE scanning).
4. **Pair Your E-Bike as a Sensor:**
   * Power on your Shimano e-bike.
   * On the Karoo 3, navigate to **Sensors -> “+” (Add) -> Search**.
   * Select your discovered bike (e.g., `EWEN100...` or `Shimano STEPS`) and confirm pairing.
5. **Add Data Fields to Your Ride Profile:**
   * Go to **Profiles** -> select your e-bike profile -> **Data Pages**.
   * Add any fields from the **Shimano EP8 E-Bike** category (e.g., *EP8 Mode* / *EP8 Modus*, *EP8 Battery Bar* / *EP8 Akku Balken*, *EP8 Range* / *EP8 Reichweite*) or native Karoo sensor fields.

---

## 📁 Project Structure

```text
app/src/
├── main/
│   ├── AndroidManifest.xml
│   ├── kotlin/io/github/indigodev/karooep8/
│   │   ├── MainActivity.kt          # Permission setup & status UI (BLE & Location)
│   │   ├── BlePermissions.kt        # Runtime BLE & Location permission checks
│   │   ├── Ep8Models.kt             # AssistMode enum & telemetry data models
│   │   ├── ShimanoBleParser.kt      # Pure binary BLE packet parser (0x02, 0x03, 0x2A19)
│   │   ├── ShimanoEp8Manager.kt     # BLE scanner, sequential GATT client & auto-reconnect
│   │   └── ShimanoEp8Extension.kt   # KarooExtension service, virtual sensor & RemoteViews tiles
│   └── res/
│       ├── layout/
│       │   ├── ep8_mode_view.xml    # Layout for the Assist Mode tile (colored dot + text)
│       │   └── ep8_battery_view.xml # Layout for the Battery Bar (ProgressBars + percentage badge)
│       ├── values/
│       │   └── strings.xml          # English default strings
│       ├── values-de/
│       │   └── strings.xml          # German localized strings
│       └── xml/
│           └── extension_info.xml   # Extension & DataType registration for Karoo OS
└── test/kotlin/io/github/indigodev/karooep8/
    └── ShimanoBleParserTest.kt      # Unit tests for Shimano BLE protocol decoding
```
