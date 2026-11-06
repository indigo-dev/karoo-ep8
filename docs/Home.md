# 🚴‍♂️ Welcome to the Kteps Wiki

**Kteps** is a native extension for the **Hammerhead Karoo** (Karoo 2 & Karoo 3) that wirelessly connects to Shimano STEPS / EP8 e-bike systems via the EW-EN100 junction and compatible display units.

---

## 📑 Table of Contents

* 📖 **[Project Documentation](project-documentation.md):** 
  * **Background:** Why EW-EN100 does not use standard ANT+ LEV, but proprietary Shimano BLE
  * **Objectives:** Full wireless integration without additional hardware bridges
  * **Technical Architecture:** Reverse-engineered BLE protocol, hybrid battery polling, and Hammerhead `BluetoothCoordinator` lifecycle
* 📡 **[BLE Protocol & Byte Specification](ble-protocol.md):**
  * **Shimano GATT Architecture:** Services, Characteristics & Base UUID
  * **Hardware Compatibility & Bonding:** EW-EN100, SC-EM800, SC-E7000 vs. SC-E8000
  * **Byte-by-Byte Telemetry Mapping:** Packets `0x02`, `0x03`, and `0x01` on Characteristic `0x2AC1`
  * **Parsed vs. Unused Fields:** Di2 gearing, Profile 1/2 bitmask, trip distance, and battery polling
* 📦 **[Installation Guide](installation.md):** 
  * Enabling Developer Options & USB Debugging on the Karoo
  * Sideloading the APK via ADB or Companion App
  * Granting required Bluetooth permissions
  * Pairing the e-bike as a sensor & configuring data fields
* 🛠️ **[Troubleshooting & Logcat Guide](logcat-guide.md):** 
  * Prerequisites (Cold reboot, removing paired sensor, clean state)
  * Step-by-step ADB log recording on PC
  * What the developer inspects in the log for diagnostics
