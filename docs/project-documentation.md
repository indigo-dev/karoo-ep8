# 📖 Kteps – Project Documentation

## 1. Background & Context

Modern e-bikes powered by **Shimano STEPS / EP8 (DU-EP800 / EP801 / EP6)** motors are frequently equipped with the compact handlebar junction box **Shimano EW-EN100**.

* **Proprietary BLE Instead of ANT+ LEV:** While many other e-bike manufacturers (e.g., Specialized, Giant, Bosch) adopt the open ANT+ LEV (Light Electric Vehicle) profile, the EW-EN100 broadcasts ride data (assist mode, battery level, remaining range) **exclusively over a proprietary Shimano Bluetooth Low Energy (BLE) protocol**.
* **No Native Support on Hammerhead Karoo:** Without additional hardware bridges or third-party translators, the Hammerhead Karoo cannot discover the e-bike as a native e-bike sensor or display its assist modes and battery percentage.
* **Karoo OS Bluetooth Lifecycle Management:** The Karoo OS employs aggressive power management via its internal `io.hammerhead.sensorservice` (`BluetoothCoordinator`). If no native BLE sensors (such as a heart rate monitor) are connected, the system automatically powers down the Bluetooth chip to preserve battery life.

---

## 2. Objectives

The primary goal of **Kteps** is to provide seamless, native, wireless integration of Shimano EP8 / EW-EN100 systems on the **Hammerhead Karoo** (Karoo 2 & Karoo 3):

1. **Direct Pairing:** Seamless discovery and pairing directly from the standard Karoo Sensor menu (*Sensors -> `+` -> Search*).
2. **Custom Graphical & Numeric E-Bike Fields:**
   * **EP8 Mode:** Visual tile with a colored status dot following the official Shimano color code (`OFF`, `ECO`, `TRAIL`, `BOOST`, `WALK`).
   * **EP8 Battery Bar:** Dynamic color progress bar (Green > 50%, Yellow 21–50%, Red ≤ 20%) with a responsive percentage badge in full-width layouts.
   * **EP8 Range:** Automatic calculation of the remaining range matching the active motor assist mode.
   * **EP8 Odometer & Trip:** True drive unit lifetime distance and session-based trip distance.
3. **Virtual System Sensor:** Simultaneous feeding of motor metrics into **native Karoo system data fields**:
   * Speed (`SPEED`, including auto-pause and averages)
   * Cadence (`CADENCE`)
   * Rider Power (`POWER` in Watts)
   * Sensor Battery Level (`BATTERY_PERCENT`)
4. **Complete Stability:** Smooth operation on all production Karoo devices without requiring root privileges, developer hacks, or ADB system modifications.

---

## 3. Technical Solution & Architecture

Kteps is built on the official Hammerhead Extension SDK (**`karoo-ext`**) using a clean three-layer architecture:

```
┌────────────────────────────────────────────────────────┐
│                   Hammerhead Karoo OS                  │
│  (Sensor Menu, Profile Editor, Native Data Fields, UI) │
└───────────────────────────▲────────────────────────────┘
                            │ IPC (karoo-ext SDK)
┌───────────────────────────┴────────────────────────────┐
│                    Kteps Extension                     │
│  (KtepsExtension.kt, RemoteViews, DataPoint Emitter)   │
└───────────────────────────▲────────────────────────────┘
                            │ Kotlin StateFlow / Telemetry
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
└───────────────────────────▲────────────────────────────┘
```

### A. Reverse-Engineering Shimano BLE Protocols
* **D-Fly Telemetry Service (`000018ef-5348-494d-414e-4f5f424c4500`):**
  * Characteristic **`0x2AC1`**: Broadcasts two core packet types via GATT notifications:
    * **Packet `0x02` (Real-time Telemetry):** Assist mode, wheel speed (0.1 km/h resolution), rider power (Watts), and cadence.
    * **Packet `0x03` (Distance & Range):** 32-bit lifetime motor odometer and remaining range for Boost, Trail, and Eco.
  * *(Note: For an exhaustive byte-by-byte breakdown of all packets including unparsed Di2, profile, and fault codes, see the [BLE Protocol & Byte Specification](ble-protocol.md)).*

### B. Hybrid Battery Management
* Shimano strictly decouples telemetry from battery reporting: The battery level is exposed via the standard Bluetooth SIG **Battery Service (`0x180F` / `0x2A19`)**.
* Because many EW-EN100 firmware builds do not send push notifications for battery state changes, Kteps employs a **hybrid strategy**:
  * Incoming notifications are processed immediately.
  * An unobtrusive 10-second timer issues an active GATT read request to the bike to guarantee up-to-date state of charge.

### C. Hammerhead `BluetoothCoordinator` Synchronization
* To prevent Karoo OS from turning off the Bluetooth chip when no other native BLE sensors are paired, Kteps communicates with Hammerhead's internal lifecycle coordinator:
  * When initiating a scan or connection, Kteps broadcasts:
    `io.hammerhead.sensorservice.BLUETOOTH_REQUEST` (Extra `ID = "kteps"`).
  * Hammerhead registers Kteps in its active client registry and keeps Bluetooth powered on.
  * Upon disconnection or when the ride ends, Kteps broadcasts:
    `io.hammerhead.sensorservice.BLUETOOTH_RELEASE` (Extra `ID = "kteps"`), allowing the Karoo to return to energy-saving sleep.
