package com.vwgauges.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlin.math.sin

/**
 * Manages the Bluetooth connection to an ELM327 OBD-II adapter and polls
 * the VW Passat ECU for gauge data.
 *
 * Usage:
 *   connect(deviceAddress)   – connect and start polling
 *   startDemoMode()          – animated fake data (no adapter needed)
 *   disconnect()             – stop everything
 *
 * Observe [gaugeData] (StateFlow) for live updates.
 */
class OBDManager {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val _gaugeData = MutableStateFlow(GaugeData())
    val gaugeData: StateFlow<GaugeData> = _gaugeData

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status

    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    private var pollingJob: Job? = null
    private var baroKPa: Float = 101.3f   // cached barometric pressure

    // ── Public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String) = withContext(Dispatchers.IO) {
        try {
            _status.value = Status.CONNECTING
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: throw IllegalStateException("No Bluetooth adapter")
            val device = adapter.getRemoteDevice(deviceAddress)
            val uuid = UUID.fromString(VWPids.ELM327_BT_UUID)
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket!!.connect()
            reader = BufferedReader(InputStreamReader(socket!!.inputStream))
            writer = socket!!.outputStream
            initELM327()
            _status.value = Status.CONNECTED
            _gaugeData.value = _gaugeData.value.copy(isConnected = true, isDemoMode = false)
            startPolling()
        } catch (e: Exception) {
            _status.value = Status.ERROR
            cleanupSocket()
        }
    }

    fun startDemoMode() {
        pollingJob?.cancel()
        _status.value = Status.CONNECTED
        val t0 = System.currentTimeMillis()
        pollingJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                val t = (System.currentTimeMillis() - t0) / 1000.0
                val speed = (80f + 40f * sin(t / 15.0)).toFloat().coerceAtLeast(0f)
                val fuelRate = (0.8f + 0.6f * sin(t / 6.0).toFloat()).coerceAtLeast(0.1f) // L/h
                val fuelL100 = if (speed > 5f) (fuelRate / speed) * 100f else fuelRate * 3f
                _gaugeData.value = GaugeData(
                    oilPressure           = (3.2f + 1.4f * sin(t / 4.0).toFloat()).coerceIn(0f, 6f),
                    oilTemperature        = (98f  + 12f  * sin(t / 9.0).toFloat()).coerceIn(40f, 160f),
                    turboBoostPressure    = (0.7f + 0.8f * sin(t / 3.0).toFloat()).coerceIn(-0.5f, 2.5f),
                    coolantTemperature    = (87f  + 8f   * sin(t / 11.0).toFloat()).coerceIn(40f, 130f),
                    fuelConsumption       = fuelL100.coerceIn(0f, 25f),
                    speed                 = speed,
                    isConnected           = false,
                    isDemoMode            = true,
                    oilPressureIsEstimated = false
                )
                delay(200)
            }
        }
    }

    fun disconnect() {
        pollingJob?.cancel()
        cleanupSocket()
        _status.value = Status.DISCONNECTED
        _gaugeData.value = GaugeData()
    }

    // ── ELM327 init ──────────────────────────────────────────────────────────

    private suspend fun initELM327() = withContext(Dispatchers.IO) {
        sendCmd(VWPids.CMD_RESET);          delay(1500)
        sendCmd(VWPids.CMD_ECHO_OFF);       delay(300)
        sendCmd(VWPids.CMD_LINEFEEDS_OFF);  delay(200)
        sendCmd(VWPids.CMD_SPACES_OFF);     delay(200)
        sendCmd(VWPids.CMD_HEADERS_OFF);    delay(200)
        sendCmd(VWPids.CMD_ALLOW_LONG);     delay(200)
        sendCmd(VWPids.CMD_AUTO_PROTOCOL);  delay(800)

        // Cache barometric pressure once (ambient, changes rarely)
        val baroResp = sendQuery(VWPids.BARO_PRESSURE)
        parseSingleByte(baroResp, "4133")?.let { baroKPa = it.toFloat() }
    }

    // ── Polling loop ─────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var lastSpeed = 0f
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val coolant  = queryFloat(VWPids.COOLANT_TEMP)  { parseSingleByte(it, "4105")?.minus(40) }
                    val oilTemp  = queryFloat(VWPids.OIL_TEMP)      { parseSingleByte(it, "415C")?.minus(40) }
                    val mapKPa   = queryFloat(VWPids.MAP_PRESSURE)   { parseSingleByte(it, "410B")?.toFloat() }
                    val speed    = queryFloat(VWPids.VEHICLE_SPEED)  { parseSingleByte(it, "410D")?.toFloat() }
                    val fuelRate = queryFloat(VWPids.FUEL_RATE)      { parseTwoBytes(it, "415E")?.let { (a,b) -> (a*256+b)*0.05f } }
                    val load     = queryFloat(VWPids.ENGINE_LOAD)    { parseSingleByte(it, "4104")?.let { b -> b*100f/255f } }

                    if (speed != null) lastSpeed = speed

                    val boostBar = mapKPa?.let { (it - baroKPa) / 100f } ?: 0f
                    val fuelL100 = when {
                        fuelRate != null && lastSpeed > 3f -> (fuelRate / lastSpeed) * 100f
                        fuelRate != null                   -> fuelRate * 3f   // idle approximation
                        else                               -> 0f
                    }

                    _gaugeData.value = GaugeData(
                        oilPressure           = estimateOilPressure(load, coolant),
                        oilTemperature        = oilTemp  ?: _gaugeData.value.oilTemperature,
                        turboBoostPressure    = boostBar.coerceIn(-0.5f, 2.5f),
                        coolantTemperature    = coolant  ?: _gaugeData.value.coolantTemperature,
                        fuelConsumption       = fuelL100.coerceIn(0f, 25f),
                        speed                 = lastSpeed,
                        isConnected           = true,
                        isDemoMode            = false,
                        oilPressureIsEstimated = true
                    )
                    consecutiveErrors = 0
                    delay(350)
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors > 5) {
                        _status.value = Status.ERROR
                        _gaugeData.value = _gaugeData.value.copy(isConnected = false)
                        break
                    }
                    delay(1000)
                }
            }
        }
    }

    // ── OBD helpers ──────────────────────────────────────────────────────────

    private suspend fun queryFloat(pid: String, parser: (String) -> Float?): Float? {
        return try {
            val resp = sendQuery(pid) ?: return null
            parser(resp)
        } catch (e: Exception) { null }
    }

    private suspend fun sendCmd(cmd: String) = withContext(Dispatchers.IO) {
        writer?.write("$cmd\r".toByteArray())
        writer?.flush()
        readUntilPrompt()   // discard response
        Unit
    }

    private suspend fun sendQuery(pid: String): String? = withContext(Dispatchers.IO) {
        writer?.write("$pid\r".toByteArray())
        writer?.flush()
        readUntilPrompt().takeIf { it.isNotEmpty() && !it.contains("NO DATA") && !it.contains("ERROR") }
    }

    /**
     * Reads bytes from the socket until the ELM327 prompt '>' is received
     * or a 2-second timeout elapses. Must be called from an IO thread.
     */
    private fun readUntilPrompt(): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 2000L
        while (System.currentTimeMillis() < deadline) {
            val ready = try { reader?.ready() == true } catch (e: Exception) { break }
            if (ready) {
                val ch = try { reader!!.read() } catch (e: Exception) { -1 }
                if (ch == -1 || ch.toChar() == '>') break
                sb.append(ch.toChar())
            } else {
                Thread.sleep(8)
            }
        }
        return sb.toString().trim()
    }

    // ── Response parsers ─────────────────────────────────────────────────────

    /** Extracts the first data byte after [prefix] in a whitespace-stripped hex response. */
    private fun parseSingleByte(response: String, prefix: String): Int? {
        val clean = response.replace("\\s".toRegex(), "").uppercase()
        val idx = clean.indexOf(prefix.uppercase())
        if (idx < 0) return null
        val start = idx + prefix.length
        if (start + 2 > clean.length) return null
        return clean.substring(start, start + 2).toIntOrNull(16)
    }

    /** Extracts two data bytes (A, B) after [prefix]. */
    private fun parseTwoBytes(response: String, prefix: String): Pair<Int, Int>? {
        val clean = response.replace("\\s".toRegex(), "").uppercase()
        val idx = clean.indexOf(prefix.uppercase())
        if (idx < 0) return null
        val start = idx + prefix.length
        if (start + 4 > clean.length) return null
        val a = clean.substring(start, start + 2).toIntOrNull(16) ?: return null
        val b = clean.substring(start + 2, start + 4).toIntOrNull(16) ?: return null
        return a to b
    }

    /**
     * Estimates oil pressure from engine load and coolant temperature.
     *
     * The VW Passat uses a binary oil-pressure warning switch, not a continuous
     * pressure sensor accessible via standard OBD-II. This formula gives a
     * plausible 1.0–5.0 bar range that mimics real-world behaviour.
     *
     *   Cold engine → slightly elevated pressure (thicker oil)
     *   Idle (~15 % load) → ~1.5 bar
     *   Part throttle (~50 % load) → ~3.0 bar
     *   WOT (~100 % load) → ~5.0 bar
     */
    private fun estimateOilPressure(loadPercent: Float?, coolantTemp: Float?): Float {
        val load  = (loadPercent ?: 30f).coerceIn(0f, 100f)
        val temp  = (coolantTemp ?: 80f).coerceIn(20f, 140f)
        val cold  = if (temp < 60f) 1.0f + (60f - temp) / 100f else 1.0f
        return (1.0f + load / 100f * 4.0f) * cold
    }

    private fun cleanupSocket() {
        runCatching { socket?.close() }
        socket = null; reader = null; writer = null
    }
}
