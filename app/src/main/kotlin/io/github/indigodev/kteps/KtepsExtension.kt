package io.github.indigodev.kteps

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class ModeViewState(
    val connected: Boolean,
    val assistMode: AssistMode?
)

private data class BatteryBarViewState(
    val connected: Boolean,
    val batteryPercent: Int?
)

/**
 * Suppresses re-sending unchanged values to Karoo. A value is sent when it differs from the last
 * sent value or when [refreshIntervalMs] has elapsed, so Karoo still gets a regular refresh.
 */
internal class DataPointDeduplicator(
    private val refreshIntervalMs: Long = 5_000L
) {
    private val lastSent = mutableMapOf<String, Pair<Double, Long>>()

    fun shouldSend(key: String, value: Double): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastSent[key]
        if (previous != null && previous.first == value && now - previous.second < refreshIntervalMs) {
            return false
        }
        lastSent[key] = value to now
        return true
    }

    fun invalidate(key: String) {
        lastSent.remove(key)
    }

    fun reset() {
        lastSent.clear()
    }
}

class KtepsModeDataType(
    extension: String,
    private val ktepsManager: KtepsManager,
    private val scope: CoroutineScope
) : DataTypeImpl(extension, "kteps-mode") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = scope.launch {
            ktepsManager.telemetry
                .map { ModeViewState(it.connected, it.assistMode) }
                .distinctUntilChanged()
                .collectLatest { state ->
                    val views = RemoteViews(context.packageName, R.layout.kteps_mode_view)

                    val mode = state.assistMode
                    val (modeText, dotColor) = if (!state.connected || mode == null) {
                        "--" to AssistMode.OFF.dotColorArgb
                    } else {
                        mode.label to mode.dotColorArgb
                    }

                    views.setTextViewText(R.id.kteps_mode_dot, "●")
                    views.setTextColor(R.id.kteps_mode_dot, dotColor)
                    views.setTextViewText(R.id.kteps_mode_text, modeText)

                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

class KtepsBatteryBarDataType(
    extension: String,
    private val ktepsManager: KtepsManager,
    private val scope: CoroutineScope
) : DataTypeImpl(extension, "kteps-battery-bar") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Karoo uses a 60-column grid: 60 = full width, 30 = half width
        val isFullWidth = config.gridSize.first >= 50

        val job = scope.launch {
            ktepsManager.telemetry
                .map { BatteryBarViewState(it.connected, it.batteryPercent?.coerceIn(0, 100)) }
                .distinctUntilChanged()
                .collectLatest { state ->
                    val views = RemoteViews(context.packageName, R.layout.kteps_battery_view)
                    val knownLevel = state.batteryPercent.takeIf { state.connected }
                    val level = knownLevel ?: 0

                    views.setViewVisibility(R.id.kteps_bar_green, View.GONE)
                    views.setViewVisibility(R.id.kteps_bar_yellow, View.GONE)
                    views.setViewVisibility(R.id.kteps_bar_red, View.GONE)

                    val activeBarId = when {
                        level > 50 -> R.id.kteps_bar_green
                        level > 20 -> R.id.kteps_bar_yellow
                        else -> R.id.kteps_bar_red
                    }

                    views.setViewVisibility(activeBarId, View.VISIBLE)
                    views.setProgressBar(activeBarId, 100, level, false)

                    if (isFullWidth) {
                        val label = if (knownLevel != null) "$knownLevel %" else "-- %"
                        views.setTextViewText(R.id.kteps_battery_text, label)
                        views.setViewVisibility(R.id.kteps_battery_text, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.kteps_battery_text, View.GONE)
                    }

                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

class KtepsExtension : KarooExtension("kteps", BuildConfig.VERSION_NAME) {

    companion object {
        private const val TAG = "KtepsExtension"
        private const val UID_PREFIX = "kteps-"
        private const val BATTERY_STATUS_KEY = "battery-status"
    }

    override fun onCreate() {
        super.onCreate()
        ExtensionDiagnostics.onServiceCreated()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ktepsManager by lazy { KtepsManager(applicationContext, scope) }

    private val dtMode by lazy { DataType.dataTypeId(extension, "kteps-mode") }
    private val dtRange by lazy { DataType.dataTypeId(extension, "kteps-range") }
    private val dtBattery by lazy { DataType.dataTypeId(extension, "kteps-battery") }
    private val dtBatteryBar by lazy { DataType.dataTypeId(extension, "kteps-battery-bar") }
    private val dtPower by lazy { DataType.dataTypeId(extension, "kteps-power") }
    private val dtCadence by lazy { DataType.dataTypeId(extension, "kteps-cadence") }
    private val dtSpeed by lazy { DataType.dataTypeId(extension, "kteps-speed") }
    private val dtTrip by lazy { DataType.dataTypeId(extension, "kteps-trip") }
    private val dtOdometer by lazy { DataType.dataTypeId(extension, "kteps-odometer") }

    private val supportedDataTypes by lazy {
        listOf(
            DataType.Type.BATTERY_PERCENT,
            DataType.Type.POWER,
            DataType.Type.CADENCE,
            DataType.Type.SPEED,
            dtMode,
            dtRange,
            dtBattery,
            dtBatteryBar,
            dtPower,
            dtCadence,
            dtSpeed,
            dtTrip,
            dtOdometer
        )
    }

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            KtepsModeDataType(extension, ktepsManager, scope),
            KtepsBatteryBarDataType(extension, ktepsManager, scope)
        )
    }

    override fun startScan(emitter: Emitter<Device>) {
        ExtensionDiagnostics.onKarooScanStarted()
        val job = scope.launch {
            ktepsManager.scanForShimanoBikes().collect { bike ->
                emitter.onNext(
                    Device(
                        extension = extension,
                        uid = "$UID_PREFIX${bike.macAddress}",
                        displayName = bike.displayName,
                        dataTypes = supportedDataTypes
                    )
                )
            }
        }
        emitter.setCancellable {
            ExtensionDiagnostics.onKarooScanStopped()
            job.cancel()
        }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        ExtensionDiagnostics.onConnectRequested(uid)
        val macAddress = uid.removePrefix(UID_PREFIX)
        val connectionToken = ktepsManager.acquireConnection(macAddress)

        val job = scope.launch {
            var wasConnected = false
            var lastAssistMode: AssistMode? = null
            val dedup = DataPointDeduplicator()

            fun sendIfChanged(dataTypeId: String, field: String, value: Double) {
                if (dedup.shouldSend(dataTypeId, value)) {
                    emitter.emitSingle(dataTypeId, field, value)
                }
            }

            ktepsManager.telemetry.collect { state ->
                if (state.connected && !wasConnected) {
                    wasConnected = true
                    // Fresh connection: make sure Karoo receives every value again.
                    dedup.reset()
                    lastAssistMode = null
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                } else if (!state.connected && wasConnected) {
                    wasConnected = false
                    lastAssistMode = null
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                }

                if (state.connected) {
                    // Only report values that have actually been received from the bike, so Karoo
                    // never sees a placeholder 0 % battery / CRITICAL status right after connecting.
                    // Each value is only sent when it changed (or as a periodic refresh), instead of
                    // pushing all data points over IPC for every BLE packet.
                    state.batteryPercent?.let { percent ->
                        val batteryStatus = when {
                            percent > 80 -> BatteryStatus.NEW
                            percent > 50 -> BatteryStatus.GOOD
                            percent > 20 -> BatteryStatus.OK
                            percent > 10 -> BatteryStatus.LOW
                            else -> BatteryStatus.CRITICAL
                        }
                        if (dedup.shouldSend(BATTERY_STATUS_KEY, batteryStatus.ordinal.toDouble())) {
                            emitter.onNext(OnBatteryStatus(batteryStatus))
                        }

                        val batteryVal = percent.toDouble()
                        sendIfChanged(DataType.Type.BATTERY_PERCENT, DataType.Field.SINGLE, batteryVal)
                        sendIfChanged(dtBattery, DataType.Field.SINGLE, batteryVal)
                        sendIfChanged(dtBatteryBar, DataType.Field.SINGLE, batteryVal)
                    }

                    val mode = state.assistMode
                    val modeChanged = mode != null && mode != lastAssistMode
                    if (modeChanged) {
                        lastAssistMode = mode
                        // Assist mode changed: invalidate range deduplication so that the range for
                        // the newly active mode is immediately emitted to Karoo, even if the numeric km
                        // happens to match the previous mode's km value.
                        dedup.invalidate(dtRange)
                        Log.d(TAG, "Assist mode switched to ${mode?.label}, invalidated range dedup")
                    }

                    mode?.let {
                        sendIfChanged(dtMode, DataType.Field.SINGLE, it.code.toDouble())
                    }
                    state.activeRangeKm?.let { rangeKm ->
                        sendIfChanged(dtRange, DataType.Field.SINGLE, rangeKm.toDouble())
                    }

                    val powerVal = state.riderPowerWatts.toDouble()
                    sendIfChanged(DataType.Type.POWER, DataType.Field.POWER, powerVal)
                    sendIfChanged(dtPower, DataType.Field.SINGLE, powerVal)

                    val cadenceVal = state.cadenceRpm.toDouble()
                    sendIfChanged(DataType.Type.CADENCE, DataType.Field.CADENCE, cadenceVal)
                    sendIfChanged(dtCadence, DataType.Field.SINGLE, cadenceVal)

                    val speedMps = state.speedKmh / 3.6
                    sendIfChanged(DataType.Type.SPEED, DataType.Field.SPEED, speedMps)
                    sendIfChanged(dtSpeed, DataType.Field.SINGLE, state.speedKmh)

                    state.tripMeters?.let { meters ->
                        sendIfChanged(dtTrip, DataType.Field.SINGLE, meters / 1000.0)
                    }
                    state.odometerMeters?.let { meters ->
                        sendIfChanged(dtOdometer, DataType.Field.SINGLE, meters / 1000.0)
                    }
                }
            }
        }

        emitter.setCancellable {
            job.cancel()
            ktepsManager.releaseConnection(connectionToken)
        }
    }

    private fun Emitter<DeviceEvent>.emitSingle(dataTypeId: String, field: String, value: Double) {
        onNext(
            OnDataPoint(
                DataPoint(
                    dataTypeId = dataTypeId,
                    values = mapOf(field to value)
                )
            )
        )
    }

    override fun onDestroy() {
        ktepsManager.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
