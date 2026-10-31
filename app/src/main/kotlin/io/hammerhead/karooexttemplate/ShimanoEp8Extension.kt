package io.hammerhead.karooexttemplate

import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ShimanoEp8Extension : KarooExtension("shimano-ep8", "1.0") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var ep8Manager: ShimanoEp8Manager

    override fun onCreate() {
        super.onCreate()
        ep8Manager = ShimanoEp8Manager(applicationContext)
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
                            DataType.Type.BATTERY_PERCENT,
                            DataType.Type.CADENCE,
                            DataType.Type.SPEED,
                            DataType.dataTypeId(extension, "ep8-mode"),
                            DataType.dataTypeId(extension, "ep8-range"),
                            DataType.dataTypeId(extension, "ep8-cadence"),
                            DataType.dataTypeId(extension, "ep8-power")
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
                    // 1. Batterie-Status
                    val batteryStatus = when {
                        state.batteryPercent > 80 -> BatteryStatus.NEW
                        state.batteryPercent > 50 -> BatteryStatus.GOOD
                        state.batteryPercent > 20 -> BatteryStatus.OK
                        state.batteryPercent > 10 -> BatteryStatus.LOW
                        else -> BatteryStatus.CRITICAL
                    }
                    emitter.onNext(OnBatteryStatus(batteryStatus))

                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.BATTERY_PERCENT,
                                values = mapOf(DataType.Field.SINGLE to state.batteryPercent.toDouble())
                            )
                        )
                    )

                    // 2. Eigenleistung (Watt) über Custom-Feld
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-power"),
                                values = mapOf(DataType.Field.SINGLE to state.riderPowerWatts.toDouble())
                            )
                        )
                    )

                    // 3. Trittfrequenz (RPM)
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.CADENCE,
                                values = mapOf(DataType.Field.CADENCE to state.cadenceRpm.toDouble())
                            )
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-cadence"),
                                values = mapOf(DataType.Field.SINGLE to state.cadenceRpm.toDouble())
                            )
                        )
                    )

                    // 4. Geschwindigkeit (m/s)
                    val speedMps = state.speedKmh / 3.6
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.SPEED,
                                values = mapOf(DataType.Field.SPEED to speedMps)
                            )
                        )
                    )

                    // 5. Restreichweite (km)
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-range"),
                                values = mapOf(DataType.Field.SINGLE to state.activeRangeKm.toDouble())
                            )
                        )
                    )

                    // 6. Unterstützungsmodus (0=OFF, 1=ECO, 2=TRAIL, 3=BOOST)
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.dataTypeId(extension, "ep8-mode"),
                                values = mapOf(DataType.Field.SINGLE to state.assistMode.toDouble())
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
