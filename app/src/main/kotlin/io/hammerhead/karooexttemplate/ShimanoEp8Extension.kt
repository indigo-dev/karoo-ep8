package io.hammerhead.karooexttemplate

import android.content.Context
import android.graphics.Color
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
import kotlinx.coroutines.launch

class Ep8ModeDataType(
    extension: String,
    private val ep8Manager: ShimanoEp8Manager,
    private val scope: CoroutineScope
) : DataTypeImpl(extension, "ep8-mode") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = scope.launch {
            ep8Manager.telemetry.collectLatest { state ->
                val views = RemoteViews(context.packageName, R.layout.ep8_mode_view)

                val (modeText, dotColor) = if (!state.connected) {
                    "--" to Color.parseColor("#9E9E9E")
                } else {
                    when (state.assistMode) {
                        1 -> "ECO" to Color.parseColor("#2196F3")    // Shimano Blau
                        2 -> "TRAIL" to Color.parseColor("#4CAF50")  // Shimano Grün
                        3 -> "BOOST" to Color.parseColor("#FFA000")  // Shimano Gelb/Orange
                        4 -> "WALK" to Color.parseColor("#00BCD4")   // Türkis (Walk-Bereitschaft)
                        5 -> "WALK+" to Color.parseColor("#E040FB")  // Violett (Motor schiebt aktiv)
                        else -> "OFF" to Color.parseColor("#9E9E9E") // Grau
                    }
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
        val job = scope.launch {
            ep8Manager.telemetry.collectLatest { state ->
                val views = RemoteViews(context.packageName, R.layout.ep8_battery_view)
                val level = if (state.connected) state.batteryPercent.coerceIn(0, 100) else 0

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

                emitter.updateView(views)
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

class ShimanoEp8Extension : KarooExtension("shimano-ep8", "1.0") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ep8Manager by lazy { ShimanoEp8Manager(applicationContext) }

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
                        uid = "ep8-${bike.macAddress}",
                        displayName = bike.displayName,
                        dataTypes = listOf(
                            // Virtueller Sensor für originale Karoo-Datenfelder
                            DataType.Type.BATTERY_PERCENT,
                            DataType.Type.POWER,
                            DataType.Type.CADENCE,
                            DataType.Type.SPEED,
                            // Eigene EP8-Datenfelder
                            DataType.dataTypeId(extension, "ep8-mode"),
                            DataType.dataTypeId(extension, "ep8-range"),
                            DataType.dataTypeId(extension, "ep8-battery"),
                            DataType.dataTypeId(extension, "ep8-battery-bar"),
                            DataType.dataTypeId(extension, "ep8-power"),
                            DataType.dataTypeId(extension, "ep8-cadence"),
                            DataType.dataTypeId(extension, "ep8-speed"),
                            DataType.dataTypeId(extension, "ep8-trip"),
                            DataType.dataTypeId(extension, "ep8-odometer")
                        )
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        val macAddress = uid.removePrefix("ep8-")
        ep8Manager.connect(macAddress)

        val job = scope.launch {
            var wasConnected = false
            ep8Manager.telemetry.collectLatest { state ->
                if (state.connected && !wasConnected) {
                    wasConnected = true
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                } else if (!state.connected && wasConnected) {
                    wasConnected = false
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                }

                if (state.connected) {
                    // 1. Akkustand (%) -> System-Status + Original-Feld + EP8-Felder (Numerisch & Balken)
                    val batteryStatus = when {
                        state.batteryPercent > 80 -> BatteryStatus.NEW
                        state.batteryPercent > 50 -> BatteryStatus.GOOD
                        state.batteryPercent > 20 -> BatteryStatus.OK
                        state.batteryPercent > 10 -> BatteryStatus.LOW
                        else -> BatteryStatus.CRITICAL
                    }
                    emitter.onNext(OnBatteryStatus(batteryStatus))

                    val batteryVal = state.batteryPercent.toDouble()
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.BATTERY_PERCENT,
                                values = mapOf(DataType.Field.SINGLE to batteryVal)
                            )
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-battery"),
                                values = mapOf(DataType.Field.SINGLE to batteryVal)
                            )
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-battery-bar"),
                                values = mapOf(DataType.Field.SINGLE to batteryVal)
                            )
                        )
                    )

                    // 2. Unterstützungsmodus
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-mode"),
                                values = mapOf(DataType.Field.SINGLE to state.assistMode.toDouble())
                            )
                        )
                    )

                    // 3. Restreichweite (km) -> EIN dynamisches Feld für den aktiven Modus
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-range"),
                                values = mapOf(DataType.Field.SINGLE to state.activeRangeKm.toDouble())
                            )
                        )
                    )

                    // 4. Eigenleistung (Watt) -> Original Karoo Power-Felder + EP8-Feld
                    val powerVal = state.riderPowerWatts.toDouble()
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.POWER,
                                values = mapOf(DataType.Field.POWER to powerVal)
                            )
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-power"),
                                values = mapOf(DataType.Field.SINGLE to powerVal)
                            )
                        )
                    )

                    // 5. Trittfrequenz (RPM) -> Original Karoo Kadenz-Felder + EP8-Feld
                    val cadenceVal = state.cadenceRpm.toDouble()
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.CADENCE,
                                values = mapOf(DataType.Field.CADENCE to cadenceVal)
                            )
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-cadence"),
                                values = mapOf(DataType.Field.SINGLE to cadenceVal)
                            )
                        )
                    )

                    // 6. Geschwindigkeit -> Original Karoo Speed-Felder (m/s) + EP8-Feld (km/h)
                    val speedMps = state.speedKmh / 3.6
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.SPEED,
                                values = mapOf(DataType.Field.SPEED to speedMps)
                            )
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-speed"),
                                values = mapOf(DataType.Field.SINGLE to state.speedKmh)
                            )
                        )
                    )

                    // 7. Trip-Distanz seit Verbindungsaufbau (in km)
                    val tripKm = state.tripMeters / 1000.0
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-trip"),
                                values = mapOf(DataType.Field.SINGLE to tripKm)
                            )
                        )
                    )

                    // 8. Echte Gesamtkilometer des Motors (in km, z.B. 32.8)
                    val odoKm = state.odometerMeters / 1000.0
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-odometer"),
                                values = mapOf(DataType.Field.SINGLE to odoKm)
                            )
                        )
                    )
                }
            }
        }

        emitter.setCancellable {
            job.cancel()
            ep8Manager.disconnect()
        }
    }

    override fun onDestroy() {
        ep8Manager.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
