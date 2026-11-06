# 🚴‍♂️ Willkommen im Kteps Wiki

**Kteps** ist eine native Erweiterung für den **Hammerhead Karoo 3**, um Shimano STEPS / EP8 E-Bikes drahtlos über die EW-EN100 Schnittstelle zu koppeln.

---

## 📑 Inhaltsverzeichnis

* 📖 **[Projektdokumentation](projektdokumentation.md):** 
  * Ausgangslage (Warum EW-EN100 kein ANT+ LEV nutzt)
  * Projektziele
  * Technische Architektur (Reverse-Engineering des BLE-Protokolls, Hybrid-Batterie-Management, Hammerhead BluetoothCoordinator Lifecycle)
* 📦 **[Installationsanleitung](installation.md):** 
  * Entwickleroptionen & Sideloading auf dem Karoo freischalten
  * APK per ADB oder Companion App installieren
  * Berechtigungen erteilen
  * Sensor koppeln & Datenfelder im Profil anlegen
* 🛠️ **[Fehleranalyse & Logcat-Anleitung](logcat-anleitung.md):** 
  * Vorbereitung (Neustart, Sensor entfernen, sauberer Zustand)
  * Schritt-für-Schritt Aufzeichnung per ADB
  * Was das Log aussagt und wie du es einsendest
