package com.vwgauges.app

/**
 * Snapshot of all gauge values read from the VW Passat OBD-II port.
 *
 * oilPressure         – bar  (estimated from engine load; direct sensor not exposed on most Passats)
 * oilTemperature      – °C   (OBD-II PID 0x5C)
 * turboBoostPressure  – bar  (gauge pressure: MAP − barometric, derived from PID 0x0B / 0x33)
 * coolantTemperature  – °C   (OBD-II PID 0x05)
 * fuelConsumption     – L/100km (derived from PID 0x5E fuel rate + PID 0x0D speed)
 * batteryVoltage      – V    (OBD-II PID 0x42 control module voltage)
 */
data class GaugeData(
    val oilPressure: Float = 0f,
    val oilTemperature: Float = 0f,
    val turboBoostPressure: Float = 0f,
    val coolantTemperature: Float = 0f,
    val fuelConsumption: Float = 0f,
    val batteryVoltage: Float = 0f,
    val speed: Float = 0f,
    val isConnected: Boolean = false,
    val isDemoMode: Boolean = false,
    // True when oil pressure comes from a real sensor PID (false = load-based estimate)
    val oilPressureIsEstimated: Boolean = true
)
