package com.vwgauges.app

/**
 * OBD-II PIDs and ELM327 commands used for the VW Passat.
 *
 * Standard Mode 01 PIDs are supported on all OBD-II compliant VW Passats (2001+).
 * VW direct oil-pressure sensors are not exposed via standard OBD-II; we derive an
 * estimate from engine load and coolant temperature.
 *
 * ELM327 protocol: ISO 15765-4 CAN (ATSP6 = 11-bit, 500 kbps) is typical for Passat B6/B7/B8.
 */
object VWPids {

    // ── Standard OBD-II Mode 01 commands (send as-is to ELM327) ──────────────

    /** Engine coolant temperature. Formula: A − 40  → °C */
    const val COOLANT_TEMP = "0105"

    /** Calculated engine load (0–100 %). Formula: A × 100 / 255 */
    const val ENGINE_LOAD = "0104"

    /** Intake manifold absolute pressure. Formula: A → kPa */
    const val MAP_PRESSURE = "010B"

    /** Engine RPM. Formula: (256A + B) / 4 → rpm */
    const val ENGINE_RPM = "010C"

    /** Vehicle speed. Formula: A → km/h */
    const val VEHICLE_SPEED = "010D"

    /**
     * Engine oil temperature (supported on most modern VW Passats).
     * Formula: A − 40 → °C.
     * Returns null / NO DATA on older ECUs that don't expose it.
     */
    const val OIL_TEMP = "015C"

    /**
     * Engine fuel rate (supported on Passat B7/B8 with matching ECU).
     * Formula: (256A + B) × 0.05 → L/h
     */
    const val FUEL_RATE = "015E"

    /** Barometric (ambient) pressure. Formula: A → kPa */
    const val BARO_PRESSURE = "0133"

    /**
     * Control module voltage (battery / charging voltage).
     * Formula: (256A + B) / 1000 → V
     * Typical range: 11.5 V (engine off) to 14.8 V (alternator charging).
     */
    const val CONTROL_MODULE_VOLTAGE = "0142"

    // ── ELM327 AT commands ────────────────────────────────────────────────────

    const val CMD_RESET         = "ATZ"
    const val CMD_ECHO_OFF      = "ATE0"
    const val CMD_HEADERS_OFF   = "ATH0"
    const val CMD_LINEFEEDS_OFF = "ATL0"
    const val CMD_SPACES_OFF    = "ATS0"
    /** Auto-detect protocol (works for all Passat generations) */
    const val CMD_AUTO_PROTOCOL = "ATSP0"
    /** Allow long (> 7-byte) responses */
    const val CMD_ALLOW_LONG    = "ATAL"

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    /** Standard Serial Port Profile UUID used by all ELM327 Bluetooth adapters */
    const val ELM327_BT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
}
