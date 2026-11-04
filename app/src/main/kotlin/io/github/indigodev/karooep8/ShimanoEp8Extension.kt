package io.github.indigodev.karooep8

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class ModeViewState(
    val connected: Boolean,
    val assistMode: AssistMode
)

private data class BatteryBarViewState(
    val connected: Boolean,
    val batteryPercent: Int
)

class Ep8ModeDataType(
    extension: String,
    private val ep8Manager: ShimanoEp8Manager,
    private val scope: CoroutineScope
) : DataTypeImpl(extension, "ep8-mode") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = scope.launch {
            ep8Manager.telemetry
                .map { ModeViewState(it.connected, it.assistMode) }
                .distinctUntilChanged()
                .collectLatest { state ->
                    val views = RemoteViews(context.packageName, R.layout.ep8_mode_view)

                    val (modeText, dotColor) = if (!state.connected) {
                        "--" to AssistMode.OFF.dotColorArgb
                    } else {
                        state.assistMode.label to state.assistMode.dotColorArgb
                    }

                    views.setTextViewText(R.id.ep8_mode_dot, "●")
                    views.setTextColor(R.id.ep8_mode_dot, dotColor)
                    views.setTextViewText(R.id.ep8_mode_text, modeText)

                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

class Ep8BatteryBarDataType(
    extension: String,
    private val ep8Manager: ShimanoEp8Manager,
    private val scope: CoroutineScope
) : DataTypeImpl(extension, "ep8-battery-bar") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Karoo uses a 60-column grid: 60 = full width, 30 = half width
        val isFullWidth = config.gridSize.first >= 50

        val job = scope.launch {
            ep8Manager.telemetry
                .map { BatteryBarViewState(it.connected, it.batteryPercent.coerceIn(0, 100)) }
                .distinctUntilChanged()
                .collectLatest { state ->
                    val views = RemoteViews(context.packageName, R.layout.ep8_battery_view)
                    val level = if (state.connected) state.batteryPercent else 0

                    views.setViewVisibility(R.id.ep8_bar_green, View.GONE)
                    views.setViewVisibility(R.id.ep8_bar_yellow, View.GONE)
                    views.setViewVisibility(R.id.ep8_bar_red, View.GONE)

                    val activeBarId = when {
                        level > 50 -> R.id.ep8_bar_green
                        level > 20 -> R.id.ep8_bar_yellow
                        else -> R.id.ep8_bar_red
                    }

                    views.setViewVisibility(activeBarId, View.VISIBLE)
                    views.setProgressBar(activeBarId, 100, level, false)

                    if (isFullWidth) {
                        val label = if (state.connected) "$level %" else "-- %"
                        views.setTextViewText(R.id.ep8_battery_text, label)
                        views.setViewVisibility(R.id.ep8_battery_text, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.ep8_battery_text, View.GONE)
                    }

                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

class ShimanoEp8Extension : KarooExtension("shimano-ep8", "1.1.0") {

    companion object {
        private const val UID_PREFIX = "ep8-"
        private const val SLOW_STREAM_HEARTBEAT_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ep8Manager by lazy { ShimanoEp8Manager(applicationContext, scope) }

    private val dtMode by lazy { DataType.dataTypeId(extension, "ep8-mode") }
    private val dtRange by lazy { DataType.dataTypeId(extension, "ep8-range") }
    private val dtBattery by lazy { DataType.dataTypeId(extension, "ep8-battery") }
    private val dtBatteryBar by lazy { DataType.dataTypeId(extension, "ep8-battery-bar") }
    private val dtPower by lazy { DataType.dataTypeId(extension, "ep8-power") }
    private val dtCadence by lazy { DataType.dataTypeId(extension, "ep8-cadence") }
    private val dtSpeed by lazy { DataType.dataTypeId(extension, "ep8-speed") }
    private val dtTrip by lazy { DataType.dataTypeId(extension, "ep8-trip") }
    private val dtOdometer by lazy { DataType.dataTypeId(extension, "ep8-odometer") }

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
            Ep8ModeDataType(extension, ep8Manager, scope),
            Ep8BatteryBarDataType(extension, ep8Manager, scope)
        )
    }

    override fun startScan(emitter: Emitter<Device>) {
        val job = scope.launch {
            ep8Manager.scanForShimanoBikes().collect { bike ->
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
        emitter.setCancellable { job.cancel() }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        val macAddress = uid.removePrefix(UID_PREFIX)
        emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
        ep8Manager.connect(macAddress)

        val job = scope.launch {
            var wasConnected = false
            var lastBatteryStatus: BatteryStatus? = null
            var lastSlowSnapshot: SlowTelemetrySnapshot? = null
            var lastSlowEmitElapsedMs = 0L

            ep8Manager.telemetry.collectLatest { state ->
                if (state.connected && !wasConnected) {
                    wasConnected = true
                    lastBatteryStatus = null
                    lastSlowSnapshot = null
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                } else if (!state.connected && wasConnected) {
                    wasConnected = false
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                }

                if (!state.connected) return@collectLatest

                // 1. Live high-frequency metrics (Power, Cadence, Speed)
                val powerVal = state.riderPowerWatts.toDouble()
                emitter.emitSingle(DataType.Type.POWER, DataType.Field.POWER, powerVal)
                emitter.emitSingle(dtPower, DataType.Field.SINGLE, powerVal)

                val cadenceVal = state.cadenceRpm.toDouble()
                emitter.emitSingle(DataType.Type.CADENCE, DataType.Field.CADENCE, cadenceVal)
                emitter.emitSingle(dtCadence, DataType.Field.SINGLE, cadenceVal)

                val speedMps = state.speedKmh / 3.6
                emitter.emitSingle(DataType.Type.SPEED, DataType.Field.SPEED, speedMps)
                emitter.emitSingle(dtSpeed, DataType.Field.SINGLE, state.speedKmh)

                // 2. Battery status badge (emit only on threshold change)
                val batteryStatus = when {
                    state.batteryPercent > 80 -> BatteryStatus.NEW
                    state.batteryPercent > 50 -> BatteryStatus.GOOD
                    state.batteryPercent > 20 -> BatteryStatus.OK
                    state.batteryPercent > 10 -> BatteryStatus.LOW
                    else -> BatteryStatus.CRITICAL
                }
                if (batteryStatus != lastBatteryStatus) {
                    lastBatteryStatus = batteryStatus
                    emitter.onNext(OnBatteryStatus(batteryStatus))
                }

                // 3. Slow-changing state metrics: emit immediately on change or every 2s as heartbeat
                val snapshot = SlowTelemetrySnapshot(
                    batteryPercent = state.batteryPercent,
                    assistModeCode = state.assistMode.code,
                    activeRangeKm = state.activeRangeKm,
                    tripMeters = state.tripMeters,
                    odometerMeters = state.odometerMeters
                )
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (snapshot != lastSlowSnapshot || nowElapsedMs - lastSlowEmitElapsedMs >= SLOW_STREAM_HEARTBEAT_MS) {
                    lastSlowSnapshot = snapshot
                    lastSlowEmitElapsedMs = nowElapsedMs

                    val batteryVal = state.batteryPercent.toDouble()
                    emitter.emitSingle(DataType.Type.BATTERY_PERCENT, DataType.Field.SINGLE, batteryVal)
                    emitter.emitSingle(dtBattery, DataType.Field.SINGLE, batteryVal)
                    emitter.emitSingle(dtBatteryBar, DataType.Field.SINGLE, batteryVal)
                    emitter.emitSingle(dtMode, DataType.Field.SINGLE, state.assistMode.code.toDouble())
                    emitter.emitSingle(dtRange, DataType.Field.SINGLE, state.activeRangeKm.toDouble())
                    emitter.emitSingle(dtTrip, DataType.Field.SINGLE, state.tripMeters / 1000.0)
                    emitter.emitSingle(dtOdometer, DataType.Field.SINGLE, state.odometerMeters / 1000.0)
                }
            }
        }

        emitter.setCancellable {
            job.cancel()
            ep8Manager.disconnect()
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

    private data class SlowTelemetrySnapshot(
        val batteryPercent: Int,
        val assistModeCode: Int,
        val activeRangeKm: Int,
        val tripMeters: Long,
        val odometerMeters: Long
    )

    override fun onDestroy() {
        ep8Manager.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
