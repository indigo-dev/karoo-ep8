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
import kotlinx.coroutines.launch
import java.util.UUID

class ShimanoEp8Manager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "KarooEP8"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val FAST_RECONNECT_DELAY_MS = 600L
        private const val DISCONNECT_GRACE_MS = 5_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 250L
    }

    private val _telemetry = MutableStateFlow(Ep8Telemetry())
    val telemetry: StateFlow<Ep8Telemetry> = _telemetry.asStateFlow()

    private val gattLock = Any()
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetMacAddress: String? = null
    private var reconnectJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var serviceDiscoveryJob: Job? = null
    private var activeScanCallback: ScanCallback? = null
    private var nextToken = 1L
    private val activeTokens = mutableSetOf<Long>()

    @Volatile
    private var sessionStartOdoMeters: Long? = null

    private fun bluetoothManager(): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun bluetoothAdapter(): BluetoothAdapter? =
        bluetoothManager()?.adapter

    private fun isLeEnabled(adapter: BluetoothAdapter): Boolean {
        if (adapter.isEnabled) return true
        return runCatching {
            val method = adapter.javaClass.getMethod("isLeEnabled")
            method.invoke(adapter) as? Boolean ?: false
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun ensureBleEnabled(adapter: BluetoothAdapter): Boolean {
        // Register as a BLE app in BluetoothManagerService so Hammerhead's BluetoothCoordinator
        // cannot tear down GattService when SensorSearchActivity closes, and so BLE can run
        // in STATE_BLE_ON ("LE only mode") without triggering BluetoothCoordinator's STATE_ON disable.
        val enableBleResult = runCatching {
            val method = adapter.javaClass.getMethod("enableBLE")
            method.invoke(adapter) as? Boolean ?: false
        }.getOrDefault(false)

        if (isLeEnabled(adapter)) {
            return true
        }

        val enableResult = if (!enableBleResult) {
            runCatching {
                @Suppress("DEPRECATION")
                adapter.enable()
            }.getOrDefault(false)
        } else {
            false
        }

        Log.i(TAG, "Requested Bluetooth LE enable (enableBLE=$enableBleResult, enable=$enableResult)")
        ExtensionDiagnostics.onGattEvent("enableBLE($enableBleResult/$enableResult)")
        return isLeEnabled(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan() {
        val callback = synchronized(gattLock) {
            val current = activeScanCallback
            activeScanCallback = null
            current
        } ?: return
        runCatching {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(callback)
            Log.i(TAG, "Stopped active BLE scan before GATT connect")
        }
    }

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
        if (adapter != null) {
            ensureBleEnabled(adapter)
        }
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            val msg = "Bluetooth LE scanner unavailable (Bluetooth off?)"
            Log.w(TAG, msg)
            ExtensionDiagnostics.onError(msg)
            close()
            return@callbackFlow
        }
        ExtensionDiagnostics.clearError()

        val seenMacs = mutableSetOf<String>()

        fun emitConnectedIfShimano(device: BluetoothDevice) {
            val mac = device.address ?: return
            val name = runCatching { device.name.orEmpty() }.getOrDefault("")
            val uuids = runCatching {
                device.uuids?.mapNotNull { it?.uuid } ?: emptyList()
            }.getOrDefault(emptyList())
            if (ShimanoBleParser.isShimanoAdvertisement(name, uuids) && seenMacs.add(mac)) {
                val display = name.ifBlank { "Shimano STEPS ($mac)" }
                Log.i(TAG, "Found active Shimano Bike: $display [$mac]")
                ExtensionDiagnostics.onDeviceReported("$display [$mac]")
                trySend(DiscoveredShimanoBike(macAddress = mac, displayName = display))
            }
        }

        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).forEach(::emitConnectedIfShimano)
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
            synchronized(gattLock) {
                activeScanCallback = scanCallback
            }
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
            synchronized(gattLock) {
                if (activeScanCallback === scanCallback) {
                    activeScanCallback = null
                }
            }
            if (scanStarted) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping BLE scan", e)
                }
            }
        }
    }

    fun acquireConnection(macAddress: String): Long {
        val token: Long
        val shouldOpenNewGatt: Boolean
        synchronized(gattLock) {
            token = nextToken++
            activeTokens.add(token)
            ExtensionDiagnostics.onConsumersChanged(activeTokens.size)
            disconnectGraceJob?.cancel()
            disconnectGraceJob = null

            if (targetMacAddress == macAddress && (_telemetry.value.connected || bluetoothGatt != null)) {
                shouldOpenNewGatt = false
            } else {
                if (targetMacAddress != null && targetMacAddress != macAddress) {
                    sessionStartOdoMeters = null
                }
                targetMacAddress = macAddress
                reconnectJob?.cancel()
                reconnectJob = null
                closeGattLocked()
                shouldOpenNewGatt = true
            }
        }
        if (shouldOpenNewGatt) {
            openGattConnection(macAddress)
        }
        return token
    }

    fun releaseConnection(token: Long) {
        synchronized(gattLock) {
            activeTokens.remove(token)
            ExtensionDiagnostics.onConsumersChanged(activeTokens.size)
            if (activeTokens.isNotEmpty()) {
                return
            }
            disconnectGraceJob?.cancel()
            disconnectGraceJob = scope.launch {
                delay(DISCONNECT_GRACE_MS)
                val shouldDisconnect = synchronized(gattLock) {
                    activeTokens.isEmpty()
                }
                if (shouldDisconnect) {
                    disconnect()
                }
            }
        }
    }

    fun disconnect() {
        synchronized(gattLock) {
            activeTokens.clear()
            ExtensionDiagnostics.onConsumersChanged(0)
            disconnectGraceJob?.cancel()
            disconnectGraceJob = null
            targetMacAddress = null
            reconnectJob?.cancel()
            reconnectJob = null
            closeGattLocked()
            sessionStartOdoMeters = null
        }
        _telemetry.value = Ep8Telemetry(connected = false)
    }

    @SuppressLint("MissingPermission")
    private fun openGattConnection(macAddress: String) {
        synchronized(gattLock) {
            if (targetMacAddress != macAddress) return
            if (_telemetry.value.connected || bluetoothGatt != null) return
        }

        if (!BlePermissions.hasConnectPermissions(context)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission; retrying in ${RECONNECT_DELAY_MS}ms")
            ExtensionDiagnostics.onError("Missing BLUETOOTH_CONNECT")
            scheduleReconnect(macAddress)
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter unavailable; retrying in ${RECONNECT_DELAY_MS}ms")
            ExtensionDiagnostics.onError("Bluetooth adapter null")
            scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
            return
        }

        if (!ensureBleEnabled(adapter)) {
            Log.w(TAG, "Bluetooth adapter off — requested enable, retrying in ${FAST_RECONNECT_DELAY_MS}ms")
            ExtensionDiagnostics.onError("Enabling Bluetooth...")
            scheduleReconnect(macAddress, FAST_RECONNECT_DELAY_MS)
            return
        }

        ExtensionDiagnostics.clearError()
        stopActiveScan()

        try {
            val device = adapter.getRemoteDevice(macAddress)
            Log.i(TAG, "Connecting to Shimano E-Bike ($macAddress)...")
            ExtensionDiagnostics.onGattEvent("connectGatt($macAddress)")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            synchronized(gattLock) {
                if (targetMacAddress == macAddress && bluetoothGatt == null) {
                    bluetoothGatt = gatt
                } else {
                    gatt?.close()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to $macAddress", e)
            ExtensionDiagnostics.onError("SecurityException: ${e.message}")
            scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $macAddress", e)
            ExtensionDiagnostics.onError("Connect error: ${e.message}")
            scheduleReconnect(macAddress, RECONNECT_DELAY_MS)
        }
    }

    private fun scheduleReconnect(macAddress: String, delayMs: Long = RECONNECT_DELAY_MS) {
        synchronized(gattLock) {
            if (targetMacAddress != macAddress) return
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                val shouldRetry = synchronized(gattLock) {
                    targetMacAddress == macAddress &&
                        !_telemetry.value.connected &&
                        bluetoothGatt == null
                }
                if (shouldRetry) {
                    openGattConnection(macAddress)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattLocked() {
        serviceDiscoveryJob?.cancel()
        serviceDiscoveryJob = null
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
                    serviceDiscoveryJob?.cancel()
                    serviceDiscoveryJob = scope.launch {
                        delay(SERVICE_DISCOVERY_DELAY_MS)
                        if (isActiveGatt(gatt)) {
                            try {
                                gatt.discoverServices()
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException during discoverServices", e)
                            }
                        }
                    }
                }
                ExtensionDiagnostics.onGattConnected()
                _telemetry.update { it.copy(connected = true) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from ${gatt.device.address} (status=$status)")
                ExtensionDiagnostics.onGattDisconnected(status)
                synchronized(gattLock) {
                    serviceDiscoveryJob?.cancel()
                    serviceDiscoveryJob = null
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
                    val delayMs = if (status == 255) RECONNECT_DELAY_MS else FAST_RECONNECT_DELAY_MS
                    scheduleReconnect(mac, delayMs)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActiveGatt(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed with status=$status")
                ExtensionDiagnostics.onGattEvent("discoverFail($status)")
                return
            }
            ExtensionDiagnostics.onGattEvent("servicesDiscovered")

            // Match original proven handshake: read Battery Level (0x2A19) first, then enable 0x2AC1 notifications
            val batteryChar = gatt.getService(ShimanoBleParser.SERVICE_BATTERY)
                ?.getCharacteristic(ShimanoBleParser.CHAR_BATTERY_LEVEL)
            if (batteryChar != null) {
                val readInitiated = try {
                    gatt.readCharacteristic(batteryChar)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read battery characteristic", e)
                    false
                }
                if (!readInitiated) {
                    enableTelemetryNotifications(gatt)
                }
            } else {
                enableTelemetryNotifications(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(gatt, characteristic, characteristic.value, status)
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

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int
    ) {
        if (!isActiveGatt(gatt)) return
        if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == ShimanoBleParser.CHAR_BATTERY_LEVEL) {
            val level = ShimanoBleParser.parseBatteryLevel(value) ?: 0
            _telemetry.update { it.copy(batteryPercent = level) }
            ExtensionDiagnostics.onGattEvent("battery($level%)")
        }
        enableTelemetryNotifications(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetryNotifications(gatt: BluetoothGatt) {
        val telemetryChar = gatt.getService(ShimanoBleParser.SERVICE_SHIMANO_DFLY)
            ?.getCharacteristic(ShimanoBleParser.CHAR_SHIMANO_TELEMETRY)
        if (telemetryChar == null) {
            ExtensionDiagnostics.onGattEvent("missing 0x2AC1")
            return
        }
        try {
            gatt.setCharacteristicNotification(telemetryChar, true)
            val descriptor = telemetryChar.getDescriptor(ShimanoBleParser.CCCD_UUID) ?: return
            writeCccdDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            ExtensionDiagnostics.onGattEvent("notify(0x2AC1)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable notifications for 0x2AC1", e)
            ExtensionDiagnostics.onError("Notify error: ${e.message}")
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
