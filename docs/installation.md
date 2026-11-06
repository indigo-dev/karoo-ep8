# 📦 Installation Guide (Step by Step)

This guide walks you through the initial setup and sideloading of **Kteps** on your **Hammerhead Karoo** (Karoo 2 & Karoo 3).

---

## Step 1: Enable Sideloading & Developer Options on the Karoo

Because Kteps is a third-party extension, **USB Debugging** must be enabled once on your Karoo device:

1. **Open Settings:**
   * Swipe down from the top of the Karoo screen and tap the **Gear icon** (Settings).
2. **Unlock Developer Options:**
   * Scroll down to **About** (or **Device Info**).
   * Locate the **Build Number** entry.
   * Tap the **Build Number 7 times quickly**.
   * A toast notification will appear: *“You are now a developer!”*.
3. **Turn on USB Debugging:**
   * Return to the main Settings menu and navigate to **System** -> **Developer options**.
   * Find the **USB debugging** toggle (Android Debug Bridge) and switch it **ON**.
   * Confirm the security prompt by tapping **OK**.

---

## Step 2: Sideload Kteps APK onto the Karoo

Download the latest `kteps.apk` from the [GitHub Releases page](https://github.com/indigo-dev/kteps/releases) to your computer.

### Option A: Installation via ADB (Recommended)

1. Connect your Karoo to your computer using a **USB-C cable**.
2. If prompted on the Karoo display with *“Allow USB debugging?”*:
   * Check *“Always allow from this computer”* and tap **Allow**.
3. Open a terminal (Command Prompt or PowerShell on Windows, Terminal on macOS/Linux):
   ```bash
   adb install -r kteps.apk
   ```
4. After a few seconds, the message **`Success`** will appear.

*(Note: If ADB is not yet installed on your computer, you can download the official [Google Platform-Tools](https://developer.android.com/tools/releases/platform-tools) or use a browser-based sideloading utility such as WebADB).*

### Option B: Installation via Hammerhead Companion App

If you use an Android smartphone paired with the Hammerhead Companion App, you can also sideload downloaded APK files directly to the Karoo via the companion app's file sharing features.

---

## Step 3: Grant Permissions Once

Android requires explicit user permissions for Bluetooth scanning:

1. Press the **App Drawer button** on your Karoo (grid showing installed applications).
2. Locate the **Kteps** app and tap to open it once.
3. Grant the requested **Bluetooth** (and Location if prompted) permissions until all status indicators show green checkmarks.
4. You can now exit the app — it runs automatically in the background as an extension service whenever the Karoo is on.

---

## Step 4: Pair Your E-Bike as a Sensor

1. **Turn On E-Bike:**
   * Power on your Shimano e-bike using the main power button. The LED on the EW-EN100 will flash blue, indicating it is advertising over Bluetooth LE.
2. **Start Sensor Search on Karoo:**
   * Navigate to the Karoo **Sensors** menu.
   * Tap the **`+` (Add)** button and select **Search**.
3. **Select Your Bike:**
   * Within a few seconds, your bike will appear in the search results (e.g., `EWEN100 0AB` or `Shimano STEPS`).
   * Tap on your bike and select **Add Sensor** (or confirm with the checkmark).
4. The bike will now appear in your sensor list with an extension puzzle piece icon and connect automatically.

---

## Step 5: Add Data Fields to Your Ride Profile

You can now add the new e-bike data fields to your preferred ride profiles:

1. Navigate to **Profiles** on your Karoo and select your profile.
2. Tap **Edit Pages** and select the page you wish to customize.
3. Tap a data field slot and browse the available categories:
   * **Category “Kteps E-Bike”:**
     * **EP8 Mode:** Graphical tile with colored status dot (`● OFF`, `ECO`, `TRAIL`, `BOOST`, `WALK`).
     * **EP8 Battery Bar:** Graphical tri-color battery indicator bar. In full-width fields, a percentage badge is rendered automatically.
     * **EP8 Range:** Dynamic remaining range in km matching the currently active assist mode.
     * **EP8 Battery:** Large numeric battery percentage display (`0–100 %`).
     * **EP8 Trip / Total Odo:** Current session trip distance and lifetime motor odometer.
   * **Native Karoo Sensor Fields:**
     * You can also use native Karoo fields for **Speed**, **Cadence**, **Power (Watts)**, and **Sensor Battery** — Kteps feeds them automatically from the drive unit telemetry.
