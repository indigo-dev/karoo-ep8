# 🚴‍♂️ Willkommen im Kteps Wiki

**Kteps** ist eine native Erweiterung für den **Hammerhead Karoo 3**, um Shimano STEPS / EP8 E-Bikes drahtlos über die EW-EN100 Schnittstelle zu koppeln.

---

## 📑 Inhaltsverzeichnis

* 📖 **[Projektdokumentation](projektdokumentation.md):** 
  * Ausgangslage (Warum EW-EN100 kein ANT+ LEV nutzt)
  * Projektziele
  * Technische Architektur (Reverse-Engineering des BLE-Protokolls, Hybrid-Batterie-Management, Hammerhead BluetoothCoordinator Lifecycle)
* 📡 **[BLE-Protokoll & Byte-Spezifikation](ble-protokoll.md):**
  * Shimano GATT-Architektur (Services, Characteristics & Base-UUID)
  * Hardware-Kompatibilität & Bonding (EW-EN100, SC-EM800, SC-E7000 vs. SC-E8000)
  * Byte-genaue Aufschlüsselung der Telemetrie-Pakete (`0x02`, `0x03`, `0x01` auf Characteristic `0x2AC1`)
  * Übersicht über aktive vs. ungenutzte Bytes (Di2, Profil 1/2, Trip-Distanz)
* 📦 **[Installationsanleitung](installation.md):** 
  * Entwickleroptionen & Sideloading auf dem Karoo freischalten
  * APK per ADB oder Companion App installieren
  * Berechtigungen erteilen
  * Sensor koppeln & Datenfelder im Profil anlegen
* 🛠️ **[Fehleranalyse & Logcat-Anleitung](logcat-anleitung.md):** 
  * Vorbereitung (Neustart, Sensor entfernen, sauberer Zustand)
  * Schritt-für-Schritt Aufzeichnung per ADB
  * Was das Log aussagt und wie du es einsendest
