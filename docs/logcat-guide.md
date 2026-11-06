# 🛠️ Troubleshooting: Android Logcat Guide for Support

If Kteps behaves unexpectedly on your Karoo (such as persistent `SEARCHING` state, frozen data fields, or intermittent disconnects), an **Android Logcat** capture is the most valuable diagnostic tool. It allows the developer to inspect the exact Bluetooth handshake, status error codes, and internal lifecycle events with millisecond precision.

---

## 1. Important Prerequisites Before Recording

Before capturing logs, please follow these preliminary steps:

### A. Why Should the Karoo Be Cold Rebooted?
* Android stores system logs in a circular ring buffer. If the device has been powered on for days, this buffer contains hundreds of thousands of lines of unrelated past activity.
* A **cold restart (reboot)** clears temporary logs and ensures a clean, concise capture focused purely on the connection sequence.

### B. Should the Sensor Be Removed First?
* **Yes, definitely!**
* Before starting the test, go to **Sensors** on your Karoo, tap on your Shimano sensor (`EWEN100...`), and select **Forget / Remove**.
* **Reason:** Only when pairing the sensor anew can the log reveal the full connection sequence from initial BLE advertising detection through security bonding to telemetry notification subscription.

### C. Should Kteps Be Reinstalled?
* Verify that you have the **latest release** installed (visible under *Settings -> Apps -> Kteps* or by launching the Kteps companion screen).
* If upgrading from an older version, a fresh reinstall (`adb uninstall io.github.indigodev.kteps` followed by `adb install -r kteps.apk`) eliminates cached states.

---

## 2. Step-by-Step Recording Guide

### Step 1: Prepare the Karoo
1. Turn your **e-bike OFF** initially.
2. Remove the Shimano sensor from the Karoo Sensors menu (if previously paired).
3. **Reboot the Karoo** (press and hold the Power button -> *Restart*).
4. Wait until the Karoo has completely finished booting.

### Step 2: Connect to PC & Verify ADB
1. Connect the Karoo to your computer using a **USB-C cable**.
2. Open a **Command Prompt (CMD)** or **PowerShell** window on your PC.
3. Verify ADB connectivity:
   ```bash
   adb devices
   ```
   *Your Karoo should appear with status `device` (e.g., `00446GA250720019   device`). If it shows `unauthorized`, accept the prompt on the Karoo screen.*

### Step 3: Clear the Existing Log Buffer
Clear stale system logs before beginning:
```bash
adb logcat -c
```

### Step 4: Start Live Logging to a File
Start streaming live logs into a text file:
```bash
adb logcat -v time > karoo_kteps_debug.log
```
*(The terminal window will remain open and stream data in the background — do not close it).*

### Step 5: Reproduce the Issue on the Bike
Perform the exact steps where the issue occurs:
1. Turn on your **Shimano e-bike** (power button on battery or top tube).
2. On the Karoo, open the **Sensors** menu.
3. Tap **`+` (Add)** -> **Search**.
4. Wait for the bike to be discovered and tap **Add Sensor**.
5. Return to the sensor list and monitor the connection for about **30 to 60 seconds** (check whether it changes from `SEARCHING` to `CONNECTED`).
6. If applicable, switch assist modes via the handlebar shifter (e.g., Eco to Trail).

### Step 6: Stop Recording & Submit the Log
1. Return to your computer terminal.
2. Press **`Ctrl + C`** (or `Cmd + C` on macOS) to stop the recording.
3. The file **`karoo_kteps_debug.log`** will be saved in your current working directory.
4. Attach this file to a [GitHub Issue](https://github.com/indigo-dev/kteps/issues) or share it directly with the developer.

---

## 3. What the Developer Checks in the Log

With this log file, the root cause can typically be pinpointed in minutes:
* **`BluetoothCoordinator` Status:** Does Hammerhead confirm `clients=[kteps]`, or is a system service powering off the Bluetooth chip?
* **GATT Handshake:** Did characteristic descriptor writes succeed for `0x2AC1` (telemetry) and `0x2A19` (battery)?
* **GATT Error Codes:** Are error codes such as `133` (connection timeout) or `255` (generic failure) returned by the bike's wireless controller?
* **Packet Reception:** Are binary `0x02` and `0x03` telemetry payloads arriving reliably at the expected intervals?
