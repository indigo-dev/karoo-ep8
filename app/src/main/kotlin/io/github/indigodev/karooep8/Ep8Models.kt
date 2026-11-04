package io.github.indigodev.karooep8

enum class AssistMode(
    val code: Int,
    val label: String,
    val dotColorArgb: Int
) {
    OFF(0, "OFF", 0xFF9E9E9E.toInt()),
    ECO(1, "ECO", 0xFF2196F3.toInt()),
    TRAIL(2, "TRAIL", 0xFF4CAF50.toInt()),
    BOOST(3, "BOOST", 0xFFFFA000.toInt()),
    WALK(4, "WALK", 0xFF00BCD4.toInt()),
    WALK_ACTIVE(5, "WALK+", 0xFFE040FB.toInt());

    companion object {
        fun fromCode(code: Int): AssistMode =
            entries.firstOrNull { it.code == code } ?: OFF
    }
}

data class DiscoveredShimanoBike(
    val macAddress: String,
    val displayName: String
)

data class Ep8Telemetry(
    val connected: Boolean = false,
    val batteryPercent: Int = 0,
    val assistMode: AssistMode = AssistMode.OFF,
    val speedKmh: Double = 0.0,
    val riderPowerWatts: Int = 0,
    val cadenceRpm: Int = 0,
    val rangeBoostKm: Int = 0,
    val rangeTrailKm: Int = 0,
    val rangeEcoKm: Int = 0,
    val odometerMeters: Long = 0L,
    val tripMeters: Long = 0L
) {
    val activeRangeKm: Int
        get() = when (assistMode) {
            AssistMode.ECO -> rangeEcoKm
            AssistMode.TRAIL -> rangeTrailKm
            AssistMode.BOOST -> rangeBoostKm
            else -> rangeEcoKm
        }
}
