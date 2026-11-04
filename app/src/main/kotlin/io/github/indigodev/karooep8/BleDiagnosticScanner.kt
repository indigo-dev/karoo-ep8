package io.github.indigodev.karooep8

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

data class BleDiagnosticReport(
    val isScanning: Boolean = false,
    val hasRun: Boolean = false,
    val rawPacketCount: Int = 0,
    val shimanoDevices: List<String> = emptyList(),
    val otherDevices: List<String> = emptyList(),
    val error: String? = null
)

object BleDiagnosticScanner {

    private const val SCAN_DURATION_MS = 10_000L
    private const val MAX_OTHER_DEVICES = 6

    @SuppressLint("MissingPermission")
    fun runTenSecondScan(context: Context): Flow<BleDiagnosticReport> = callbackFlow {
        if (!BlePermissions.hasScanPermissions(context)) {
            trySend(
                BleDiagnosticReport(
                    isScanning = false,
                    hasRun = true,
                    error = "Missing Bluetooth permissions"
                )
            )
            close()
            return@callbackFlow
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            trySend(
                BleDiagnosticReport(
                    isScanning = false,
                    hasRun = true,
                    error = "Bluetooth disabled or LE scanner unavailable"
                )
            )
            close()
            return@callbackFlow
        }

        val lock = Any()
        var packetCount = 0
        var lastError: String? = null
        val shimanoMap = linkedMapOf<String, String>()
        val otherMap = linkedMapOf<String, String>()
        val namedOtherMacs = mutableSetOf<String>()

        fun snapshotLocked(scanning: Boolean): BleDiagnosticReport {
            return BleDiagnosticReport(
                isScanning = scanning,
                hasRun = true,
                rawPacketCount = packetCount,
                shimanoDevices = shimanoMap.values.toList(),
                otherDevices = otherMap.values.toList(),
                error = lastError
            )
        }

        fun checkKnownDevice(device: BluetoothDevice, sourceLabel: String) {
            val mac = device.address ?: return
            val name = runCatching { device.name.orEmpty() }.getOrDefault("")
            val uuids = runCatching {
                device.uuids?.mapNotNull { it?.uuid } ?: emptyList()
            }.getOrDefault(emptyList())
            if (ShimanoBleParser.isShimanoAdvertisement(name, uuids)) {
                val title = name.ifBlank { "Shimano STEPS" }
                synchronized(lock) {
                    shimanoMap[mac] = "$title [$mac] ($sourceLabel)"
                }
            }
        }

        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).forEach {
                checkKnownDevice(it, "connected")
            }
        }
        runCatching {
            adapter.bondedDevices?.forEach {
                if (!shimanoMap.containsKey(it.address)) {
                    checkKnownDevice(it, "bonded")
                }
            }
        }

        trySend(synchronized(lock) { snapshotLocked(scanning = true) })

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val mac = device.address ?: return
                val name = try {
                    device.name ?: result.scanRecord?.deviceName.orEmpty()
                } catch (_: SecurityException) {
                    result.scanRecord?.deviceName.orEmpty()
                }.trim()

                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val mfgData = result.scanRecord?.manufacturerSpecificData
                val manufacturerIds = if (mfgData != null && mfgData.size() > 0) {
                    buildSet {
                        for (i in 0 until mfgData.size()) {
                            add(mfgData.keyAt(i))
                        }
                    }
                } else {
                    emptySet()
                }

                val isShimano = ShimanoBleParser.isShimanoAdvertisement(name, uuids, manufacturerIds)
                val rssi = result.rssi

                val reportToEmit = synchronized(lock) {
                    packetCount += 1
                    var listChanged = false

                    if (isShimano) {
                        val title = name.ifBlank { "Shimano STEPS" }
                        val prev = shimanoMap.put(mac, "$title [$mac] (${rssi}dBm)")
                        if (prev == null) listChanged = true
                    } else {
                        val isNamed = name.isNotEmpty()
                        val shortUuid = uuids.firstOrNull()?.toString()?.take(8)
                        val mfgHex = manufacturerIds.firstOrNull()?.let {
                            "0x" + it.toString(16).uppercase()
                        }
                        val meta = listOfNotNull(shortUuid, mfgHex).joinToString(",")
                        val metaPart = if (meta.isNotEmpty()) " {$meta}" else ""
                        val label = "${name.ifEmpty { "Unnamed" }} [$mac]$metaPart (${rssi}dBm)"

                        if (otherMap.containsKey(mac)) {
                            if (isNamed && namedOtherMacs.add(mac)) {
                                otherMap[mac] = label
                                listChanged = true
                            }
                        } else if (otherMap.size < MAX_OTHER_DEVICES) {
                            otherMap[mac] = label
                            if (isNamed) namedOtherMacs.add(mac)
                            listChanged = true
                        } else if (isNamed) {
                            val unnamedKey = otherMap.keys.firstOrNull { !namedOtherMacs.contains(it) }
                            if (unnamedKey != null) {
                                otherMap.remove(unnamedKey)
                                otherMap[mac] = label
                                namedOtherMacs.add(mac)
                                listChanged = true
                            }
                        }
                    }

                    if (listChanged || packetCount == 1 || packetCount % 10 == 0) {
                        snapshotLocked(scanning = true)
                    } else {
                        null
                    }
                }

                if (reportToEmit != null) {
                    trySend(reportToEmit)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val report = synchronized(lock) {
                    lastError = "BLE scan failed (code=$errorCode)"
                    snapshotLocked(scanning = false)
                }
                trySend(report)
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val started = try {
            scanner.startScan(null, settings, scanCallback)
            true
        } catch (e: Exception) {
            val report = synchronized(lock) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                snapshotLocked(scanning = false)
            }
            trySend(report)
            close()
            false
        }

        val timerJob = if (started) {
            launch {
                delay(SCAN_DURATION_MS)
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
                val finalReport = synchronized(lock) { snapshotLocked(scanning = false) }
                trySend(finalReport)
                close()
            }
        } else {
            null
        }

        awaitClose {
            timerJob?.cancel()
            if (started) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
            }
        }
    }
}
