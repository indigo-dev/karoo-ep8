package io.hammerhead.sampleext

import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SampleExtension : KarooExtension("shimano-ep8", "1.0") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var ep8Manager: ShimanoEp8Manager

    override fun onCreate() {
        super.onCreate()
        ep8Manager = ShimanoEp8Manager(applicationContext)
    }

    /**
     * Wird aufgerufen, wenn du auf dem Karoo 3 unter "Sensoren -> +" nach neuen Sensoren suchst
     */
    override fun startScan(emitter: Emitter<ScanState>) {
        val job = scope.launch {
            emitter.onNext(ScanState.Scanning)
            ep8Manager.scanForShimanoBikes().collect { bike ->
                val macSuffix = bike.macAddress.replace(":", "").takeLast(4)
                emitter.onNext(
                    ScanState.ScanResult(
                        Device(
                            extension = extension,
                            uid = "ep8-${bike.macAddress}",
                            displayName = bike.displayName,
                            supportedDataTypes = listOf(
                                DataType.Type.BATTERY_PERCENT,
                                DataType.Type.POWER,
                                DataType.Type.CADENCE,
                                DataType.Type.SPEED,
                                DataType.custom(extension, "ep8-mode"),
                                DataType.custom(extension, "ep8-range")
                            )
                        )
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    /**
     * Wird vom Karoo 3 automatisch aufgerufen, sobald ein gekoppeltes Bike in der Sensorliste aktiv ist
     */
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
                    // 1. E-Bike Akkustand + Karoo-Batteriestatus-System
                    emitter.onNext(
                        OnBatteryStatus(
                            BatteryStatus.fromPercentage(state.batteryPercent)
                        )
                    )
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.BATTERY_PERCENT,
                                values = mapOf(DataType.Field.SINGLE to state.batteryPercent.toDouble())
                            )
                        )
                    )

                    // 2. Fahrer-Eigenleistung (Watt)
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.POWER,
                                values = mapOf(DataType.Field.POWER to state.riderPowerWatts.toDouble())
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

                    // 4. Geschwindigkeit (Karoo erwartet m/s als internen Standardwert)
                    val speedMetersPerSec = state.speedKmh / 3.6
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.Type.SPEED,
                                values = mapOf(DataType.Field.SPEED to speedMetersPerSec)
                            )
                        )
                    )

                    // 5. Aktuelle Restreichweite im gewählten Modus (in km)
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.custom(extension, "ep8-range"),
                                values = mapOf(DataType.Field.SINGLE to state.activeRangeKm.toDouble())
                            )
                        )
                    )

                    // 6. Aktueller Unterstützungsmodus (0=OFF, 1=ECO, 2=TRAIL, 3=BOOST)
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = DataType.custom(extension, "ep8-mode"),
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
