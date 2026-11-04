package io.github.indigodev.karooep8

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class ShimanoEp8Manager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "KarooEP8"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val BATTERY_POLL_INTERVAL_MS = 60_000L
    }

    private val _telemetry = MutableStateFlow(Ep8Telemetry())
    val telemetry: StateFlow<Ep8Telemetry> = _telemetry.asStateFlow()

    private val gattLock = Any()
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetMacAddress: String? = null
    private var reconnectJob: Job? = null
    private var batteryPollJob: Job? = null

    @Volatile
    private var sessionStartOdoMeters: Long? = null

    private fun bluetoothManager(): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun bluetoothAdapter(): BluetoothAdapter? =
        bluetoothManager()?.adapter

    @SuppressLint("MissingPermission")
    fun scanForShimanoBikes(): Flow<DiscoveredShimanoBike> = callbackFlow {
        if (!BlePermissions.hasScanPermissions(context)) {
            val msg = "Missing Bluetooth scan permissions"
            Log.w(TAG, msg)
            ExtensionDiagnostics.onError(msg)
            close()
            return@callbackFlow
        }

        val manager = bluetoothManager()
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            val msg = "Bluetooth LE scanner unavailable (Bluetooth off?)"
            Log.w(TAG, msg)
            ExtensionDiagnostics.onError(msg)
            close()
            return@callbackFlow
        }

        val seenMacs = mutableSetOf<String>()

        fun emitKnownDeviceIfShimano(device: BluetoothDevice) {
            val mac = device.address ?: return
            val name = runCatching { device.name.orEmpty() }.getOrDefault("")
            val bondedUuids = runCatching {
                device.uuids?.mapNotNull { it?.uuid } ?: emptyList()
            }.getOrDefault(emptyList())
            if (ShimanoBleParser.isShimanoAdvertisement(name, bondedUuids) && seenMacs.add(mac)) {
                val display = name.ifBlank { "Shimano STEPS ($mac)" }
                Log.i(TAG, "Found bonded/connected Shimano Bike: $display [$mac]")
                ExtensionDiagnostics.onDeviceReported("$display [$mac]")
                trySend(DiscoveredShimanoBike(macAddress = mac, displayName = display))
            }
        }

        // Also surface already-connected or OS-bonded Shimano bikes (which may stop advertising while connected)
        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).forEach(::emitKnownDeviceIfShimano)
        }
        runCatching {
            adapter.bondedDevices?.forEach(::emitKnownDeviceIfShimano)
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val mac = device.address ?: return
                val name = try {
                    device.name ?: result.scanRecord?.deviceName.orEmpty()
                } catch (_: SecurityException) {
                    result.scanRecord?.deviceName.orEmpty()
                }
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

                if (ShimanoBleParser.isShimanoAdvertisement(name, uuids, manufacturerIds) && seenMacs.add(mac)) {
                    val display = name.ifBlank { "Shimano STEPS ($mac)" }
                    Log.i(TAG, "Discovered Shimano Bike: $display [$mac]")
                    ExtensionDiagnostics.onDeviceReported("$display [$mac]")
                    trySend(DiscoveredShimanoBike(macAddress = mac, displayName = display))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = "BLE Scan failed (code=$errorCode)"
                Log.e(TAG, msg)
                ExtensionDiagnostics.onError(msg)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanStarted = try {
            scanner.startScan(null, settings, scanCallback)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
            ExtensionDiagnostics.onError("SecurityException: ${e.message}")
            close()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
            ExtensionDiagnostics.onError("Scan error: ${e.message}")
            close()
            false
        }

        awaitClose {
            if (scanStarted) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping BLE scan", e)
                }
            }
        }
    }

    fun connect(macAddress: String) {
        synchronized(gattLock) {
            if (targetMacAddress == macAddress && (_telemetry.value.connected || bluetoothGatt != null)) {
                return
            }
            if (targetMacAddress != null && targetMacAddress != macAddress) {
                sessionStartOdoMeters = null
            }
            targetMacAddress = macAddress
            reconnectJob?.cancel()
            reconnectJob = null
            batteryPollJob?.cancel()
            batteryPollJob = null
            closeGattLocked()
        }
        openGattConnection(macAddress)
    }

    fun disconnect() {
        synchronized(gattLock) {
            targetMacAddress = null
            reconnectJob?.cancel()
            reconnectJob = null
            batteryPollJob?.cancel()
            batteryPollJob = null
            closeGattLocked()
            sessionStartOdoMeters = null
        }
        _telemetry.value = Ep8Telemetry(connected = false)
    }

    @SuppressLint("MissingPermission")
    private fun openGattConnection(macAddress: String) {
        synchronized(gattLock) {
            if (targetMacAddress != macAddress) return
            closeGattLocked()
        }

        if (!BlePermissions.hasConnectPermissions(context)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission; retrying in ${RECONNECT_DELAY_MS}ms")
            scheduleReconnect(macAddress)
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter disabled or unavailable; retrying in ${RECONNECT_DELAY_MS}ms")
            scheduleReconnect(macAddress)
            return
        }

        try {
            val device = adapter.getRemoteDevice(macAddress)
            Log.i(TAG, "Connecting to Shimano E-Bike ($macAddress)...")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            synchronized(gattLock) {
                if (targetMacAddress == macAddress) {
                    bluetoothGatt = gatt
                } else {
                    gatt?.close()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to $macAddress", e)
            scheduleReconnect(macAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $macAddress", e)
            scheduleReconnect(macAddress)
        }
    }

    private fun scheduleReconnect(macAddress: String) {
        synchronized(gattLock) {
            if (targetMacAddress != macAddress) return
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                val shouldRetry = synchronized(gattLock) {
                    targetMacAddress == macAddress && !_telemetry.value.connected
                }
                if (shouldRetry) {
                    openGattConnection(macAddress)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattLocked() {
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        try {
            gatt.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing BluetoothGatt", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = synchronized(gattLock) {
                if (gatt !== bluetoothGatt) {
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                targetMacAddress
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to ${gatt.device.address}, discovering services...")
                synchronized(gattLock) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                }
                _telemetry.update { it.copy(connected = true) }
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during discoverServices", e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from ${gatt.device.address} (status=$status)")
                synchronized(gattLock) {
                    batteryPollJob?.cancel()
                    batteryPollJob = null
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                }
                _telemetry.update {
                    it.copy(
                        connected = false,
                        speedKmh = 0.0,
                        riderPowerWatts = 0,
                        cadenceRpm = 0
                    )
                }
                if (mac != null) {
                    scheduleReconnect(mac)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActiveGatt(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed with status=$status")
                return
            }

            if (!enableNotifications(gatt, ShimanoBleParser.SERVICE_SHIMANO_DFLY, ShimanoBleParser.CHAR_SHIMANO_TELEMETRY)) {
                setupBatteryMonitoring(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isActiveGatt(gatt)) return
            when (descriptor.characteristic.uuid) {
                ShimanoBleParser.CHAR_SHIMANO_TELEMETRY -> setupBatteryMonitoring(gatt)
                ShimanoBleParser.CHAR_BATTERY_LEVEL -> {
                    readBatteryCharacteristic(gatt)
                    startBatteryPolling(gatt)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isActiveGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicData(characteristic.uuid, value)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (!isActiveGatt(gatt)) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handleCharacteristicData(characteristic.uuid, characteristic.value)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isActiveGatt(gatt)) return
            handleCharacteristicData(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (!isActiveGatt(gatt)) return
                handleCharacteristicData(characteristic.uuid, characteristic.value)
            }
        }
    }

    private fun isActiveGatt(gatt: BluetoothGatt): Boolean =
        synchronized(gattLock) { gatt === bluetoothGatt }

    private fun setupBatteryMonitoring(gatt: BluetoothGatt) {
        if (!enableNotifications(gatt, ShimanoBleParser.SERVICE_BATTERY, ShimanoBleParser.CHAR_BATTERY_LEVEL)) {
            readBatteryCharacteristic(gatt)
            startBatteryPolling(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): Boolean {
        val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
            ?: return false
        return try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(ShimanoBleParser.CCCD_UUID) ?: return false
            writeCccdDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable notifications for $characteristicUuid", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCccdDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryCharacteristic(gatt: BluetoothGatt) {
        val batteryChar = gatt.getService(ShimanoBleParser.SERVICE_BATTERY)
            ?.getCharacteristic(ShimanoBleParser.CHAR_BATTERY_LEVEL) ?: return
        try {
            gatt.readCharacteristic(batteryChar)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery characteristic", e)
        }
    }

    private fun startBatteryPolling(gatt: BluetoothGatt) {
        synchronized(gattLock) {
            if (gatt !== bluetoothGatt) return
            batteryPollJob?.cancel()
            batteryPollJob = scope.launch {
                while (isActive) {
                    delay(BATTERY_POLL_INTERVAL_MS)
                    val currentGatt = synchronized(gattLock) {
                        bluetoothGatt?.takeIf { it === gatt && _telemetry.value.connected }
                    } ?: break
                    readBatteryCharacteristic(currentGatt)
                }
            }
        }
    }

    private fun handleCharacteristicData(uuid: UUID, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return

        when (uuid) {
            ShimanoBleParser.CHAR_SHIMANO_TELEMETRY -> {
                _telemetry.update { current ->
                    val parsed = ShimanoBleParser.parseTelemetryPacket(
                        bytes = bytes,
                        current = current,
                        sessionStartOdoMeters = sessionStartOdoMeters
                    )
                    sessionStartOdoMeters = parsed.sessionStartOdoMeters
                    parsed.telemetry
                }
            }

            ShimanoBleParser.CHAR_BATTERY_LEVEL -> {
                val level = ShimanoBleParser.parseBatteryLevel(bytes) ?: return
                _telemetry.update { it.copy(batteryPercent = level) }
            }
        }
    }
}
