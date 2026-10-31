package io.hammerhead.sampleext

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class DiscoveredShimanoBike(
    val macAddress: String,
    val displayName: String
)

data class Ep8Telemetry(
    val connected: Boolean = false,
    val batteryPercent: Int = 0,
    val assistMode: Int = 0,          // 0=OFF, 1=ECO, 2=TRAIL, 3=BOOST, 4=WALK
    val assistModeName: String = "OFF",
    val speedKmh: Double = 0.0,
    val riderPowerWatts: Int = 0,
    val cadenceRpm: Int = 0,
    val rangeBoostKm: Int = 0,
    val rangeTrailKm: Int = 0,
    val rangeEcoKm: Int = 0,
    val tripMeters: Long = 0
) {
    val activeRangeKm: Int
        get() = when (assistMode) {
            1 -> rangeEcoKm
            2 -> rangeTrailKm
            3 -> rangeBoostKm
            else -> rangeEcoKm
        }
}

@SuppressLint("MissingPermission")
class ShimanoEp8Manager(private val context: Context) {

    companion object {
        private const val TAG = "KarooEP8"

        val SERVICE_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        val SERVICE_SHIMANO_DFLY: UUID = UUID.fromString("000018ef-5348-494d-414e-4f5f424c4500")
        val SERVICE_SHIMANO_AUTH: UUID = UUID.fromString("000018ff-5348-494d-414e-4f5f424c4500")
        val CHAR_SHIMANO_TELEMETRY: UUID = UUID.fromString("00002ac1-5348-494d-414e-4f5f424c4500")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _telemetry = MutableStateFlow(Ep8Telemetry())
    val telemetry: StateFlow<Ep8Telemetry> = _telemetry

    private var bluetoothGatt: BluetoothGatt? = null
    private var activeMacAddress: String? = null

    /**
     * Sucht nach allen Shimano E-Bikes / EW-EN100 Einheiten in Reichweite
     */
    fun scanForShimanoBikes(): Flow<DiscoveredShimanoBike> = callbackFlow {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val seenMacs = mutableSetOf<String>()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val mac = device.address ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: ""
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

                val isShimano = uuids.contains(SERVICE_SHIMANO_DFLY) ||
                        uuids.contains(SERVICE_SHIMANO_AUTH) ||
                        name.startsWith("EWEN100", ignoreCase = true) ||
                        name.startsWith("SC-E", ignoreCase = true) ||
                        name.contains("SHIMANO", ignoreCase = true)

                if (isShimano && seenMacs.add(mac)) {
                    val display = if (name.isNotBlank()) name else "Shimano STEPS ($mac)"
                    Log.i(TAG, "Discovered Shimano Bike: $display [$mac]")
                    trySend(DiscoveredShimanoBike(macAddress = mac, displayName = display))
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        awaitClose {
            scanner.stopScan(scanCallback)
        }
    }

    /**
     * Verbindet sich mit der im Karoo-Sensormenü gekoppelten MAC-Adresse
     */
    fun connect(macAddress: String) {
        if (activeMacAddress == macAddress && _telemetry.value.connected) return
        disconnect()
        activeMacAddress = macAddress

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = adapter.getRemoteDevice(macAddress)
        Log.i(TAG, "Connecting to Shimano E-Bike ($macAddress)...")
        bluetoothGatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        activeMacAddress = null
        _telemetry.value = Ep8Telemetry(connected = false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to ${gatt.device.address}, discovering services...")
                _telemetry.value = _telemetry.value.copy(connected = true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from ${gatt.device.address}")
                _telemetry.value = _telemetry.value.copy(
                    connected = false,
                    speedKmh = 0.0,
                    riderPowerWatts = 0,
                    cadenceRpm = 0
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val batteryChar = gatt.getService(SERVICE_BATTERY)?.getCharacteristic(CHAR_BATTERY_LEVEL)
            if (batteryChar != null) {
                gatt.readCharacteristic(batteryChar)
            } else {
                enableTelemetryNotifications(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_BATTERY_LEVEL) {
                val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                _telemetry.value = _telemetry.value.copy(batteryPercent = level.coerceIn(0, 100))
            }
            enableTelemetryNotifications(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            if (characteristic.uuid == CHAR_SHIMANO_TELEMETRY) {
                parseShimanoPacket(bytes)
            } else if (characteristic.uuid == CHAR_BATTERY_LEVEL) {
                val level = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: return
                _telemetry.value = _telemetry.value.copy(batteryPercent = level.coerceIn(0, 100))
            }
        }
    }

    private fun enableTelemetryNotifications(gatt: BluetoothGatt) {
        val telemetryChar = gatt.getService(SERVICE_SHIMANO_DFLY)
            ?.getCharacteristic(CHAR_SHIMANO_TELEMETRY) ?: return
        gatt.setCharacteristicNotification(telemetryChar, true)
        val descriptor = telemetryChar.getDescriptor(CCCD_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun parseShimanoPacket(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val current = _telemetry.value

        when (bytes[0].toInt() and 0xFF) {
            // Paket 0x02 (10 Bytes): Assist-Modus, Speed, Rider Power (Watt), Cadence (RPM)
            0x02 -> {
                if (bytes.size >= 10) {
                    val mode = bytes[1].toInt() and 0xFF
                    val modeName = when (mode) {
                        0 -> "OFF"
                        1 -> "ECO"
                        2 -> "TRAIL"
                        3 -> "BOOST"
                        4 -> "WALK"
                        else -> "MODE $mode"
                    }
                    val rawSpeed = uInt16LE(bytes, 2)
                    val speedKmh = if (rawSpeed != 0xFFFF) rawSpeed / 10.0 else 0.0
                    val watts = bytes[4].toInt() and 0xFF
                    val cadence = bytes[5].toInt() and 0xFF

                    _telemetry.value = current.copy(
                        assistMode = mode,
                        assistModeName = modeName,
                        speedKmh = speedKmh,
                        riderPowerWatts = if (watts != 0xFF) watts else 0,
                        cadenceRpm = if (cadence != 0xFF) cadence else 0
                    )
                }
            }

            // Paket 0x03 (19 Bytes): Trip-Distanz & Restreichweiten (Boost, Trail, Eco)
            0x03 -> {
                if (bytes.size >= 19) {
                    val tripM = uInt32LE(bytes, 1)
                    val boostKm = uInt16LE(bytes, 13)
                    val trailKm = uInt16LE(bytes, 15)
                    val ecoKm = uInt16LE(bytes, 17)

                    _telemetry.value = current.copy(
                        tripMeters = tripM,
                        rangeBoostKm = if (boostKm != 0xFFFF) boostKm else current.rangeBoostKm,
                        rangeTrailKm = if (trailKm != 0xFFFF) trailKm else current.rangeTrailKm,
                        rangeEcoKm = if (ecoKm != 0xFFFF) ecoKm else current.rangeEcoKm
                    )
                }
            }
        }
    }

    private fun uInt16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun uInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
