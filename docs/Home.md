# 🚴‍♂️ Willkommen im Kteps Wiki

**Kteps** ist eine native Erweiterung für den **Hammerhead Karoo** (Karoo 2 & Karoo 3), um Shimano STEPS / EP8 E-Bikes drahtlos über die EW-EN100 Schnittstelle zu koppeln.

---

## 📑 Inhaltsverzeichnis

* 📖 **[Projektdokumentation](projektdokumentation.md):** 
  * **Ausgangslage:** Warum EW-EN100 kein ANT+ LEV nutzt, sondern proprietäres Shimano BLE
  * **Projektziele:** Vollständige Integration ohne Zusatz-Hardware
  * **Technische Lösung:** Reverse-Engineering des BLE-Protokolls, Hybrid-Batterie-Management und Hammerhead `BluetoothCoordinator` Lifecycle
* 📡 **[BLE-Protokoll & Byte-Spezifikation](ble-protokoll.md):**
  * **Shimano GATT-Architektur:** Services, Characteristics & Base-UUID
  * **Hardware-Kompatibilität & Bonding:** EW-EN100, SC-EM800, SC-E7000 vs. SC-E8000
  * **Byte-Aufschlüsselung:** Pakete `0x02`, `0x03` und `0x01` auf Characteristic `0x2AC1`
  * **Übersicht der Bytes:** Di2-Gänge, Profil 1/2 Bitmaske, Trip-Distanz & Akkupooling
* 📦 **[Installationsanleitung](installation.md):** 
  * Entwickleroptionen & Sideloading (USB-Debugging) auf dem Karoo freischalten
  * APK per ADB oder Companion App installieren
  * Berechtigungen erteilen
  * Sensor koppeln & Datenfelder im Profil anlegen
* 🛠️ **[Fehleranalyse & Logcat-Anleitung](logcat-anleitung.md):** 
  * Vorbereitung (Warum Kaltstart/Neustart nötig ist, Sensor vorher entfernen)
  * Schritt-für-Schritt-Aufzeichnung per ADB am PC
  * Was das Log aussagt und wie du es für den Support bereitstellst
