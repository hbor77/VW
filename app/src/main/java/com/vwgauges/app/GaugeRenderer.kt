package com.vwgauges.app

import android.graphics.*
import android.view.Surface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ── Data types ────────────────────────────────────────────────────────────────

data class ColorZone(val min: Float, val max: Float, val color: Int)

data class GaugeConfig(
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val value: Float,
    val zones: List<ColorZone>,
    val hasData: Boolean = true,
    val isEstimated: Boolean = false
) {
    /** Returns the colour of the zone that contains [value]. */
    fun valueColor(): Int =
        zones.firstOrNull { value >= it.min && value <= it.max }?.color
            ?: zones.lastOrNull()?.color
            ?: Color.WHITE

    /** Format the numeric display (0 decimals for wide ranges, 1 for narrow). */
    fun formatValue(): String = if (max - min >= 50) "%.0f".format(value) else "%.1f".format(value)
}

// ── Renderer ──────────────────────────────────────────────────────────────────

/**
 * Draws five circular arc gauges onto an Android Auto [Surface].
 *
 * Layout – 3 gauges on the top row, 2 centred on the bottom row:
 *
 *   [Oil Pressure]  [Oil Temp]  [Boost]
 *        [Coolant Temp]  [Fuel Consumption]
 *
 * Arc geometry: startAngle = 135°, sweepAngle = 270° (classic speedometer shape,
 * gap at the bottom). In Android Canvas angles are measured clockwise from 3 o'clock.
 */
class GaugeRenderer(private val surface: Surface, val width: Int, val height: Int) {

    companion object {
        private const val ARC_START  = 135f
        private const val ARC_SWEEP  = 270f
        private const val TICK_COUNT = 10
    }

    // ── Shared paint objects (allocated once) ─────────────────────────────────

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#07070F")
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#23233A")
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#181828")
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.DEFAULT
        color = Color.parseColor("#7799BB")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.parseColor("#8899BB")
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Public render entry point ─────────────────────────────────────────────

    fun render(data: GaugeData) {
        if (!surface.isValid) return
        val canvas = try { surface.lockCanvas(null) } catch (e: Exception) { return }
        try {
            drawFrame(canvas, data)
        } finally {
            try { surface.unlockCanvasAndPost(canvas) } catch (e: Exception) { /* surface gone */ }
        }
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    private fun drawFrame(canvas: Canvas, data: GaugeData) {
        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val configs = buildConfigs(data)
        val positions = gaugePositions()

        for (i in configs.indices) {
            val (cx, cy, r) = positions[i]
            drawGauge(canvas, cx, cy, r, configs[i])
        }

        drawStatusBar(canvas, data)
    }

    // ── Build gauge configs from live data ───────────────────────────────────

    private fun buildConfigs(d: GaugeData): List<GaugeConfig> = listOf(
        GaugeConfig(
            label = "OIL PRESSURE",
            unit  = "bar",
            min   = 0f, max = 6f,
            value = d.oilPressure.coerceIn(0f, 6f),
            zones = listOf(
                ColorZone(0f,  1f,  Color.parseColor("#FF2233")),
                ColorZone(1f,  2f,  Color.parseColor("#FFAA00")),
                ColorZone(2f,  5f,  Color.parseColor("#00DD77")),
                ColorZone(5f,  6f,  Color.parseColor("#FFAA00"))
            ),
            hasData      = d.isConnected || d.isDemoMode,
            isEstimated  = d.oilPressureIsEstimated
        ),
        GaugeConfig(
            label = "OIL TEMP",
            unit  = "°C",
            min   = 40f, max = 160f,
            value = d.oilTemperature.coerceIn(40f, 160f),
            zones = listOf(
                ColorZone(40f,  70f,  Color.parseColor("#4488FF")),
                ColorZone(70f,  80f,  Color.parseColor("#FFAA00")),
                ColorZone(80f,  130f, Color.parseColor("#00DD77")),
                ColorZone(130f, 150f, Color.parseColor("#FFAA00")),
                ColorZone(150f, 160f, Color.parseColor("#FF2233"))
            ),
            hasData = d.isConnected || d.isDemoMode
        ),
        GaugeConfig(
            label = "BOOST",
            unit  = "bar",
            min   = -0.5f, max = 2.5f,
            value = d.turboBoostPressure.coerceIn(-0.5f, 2.5f),
            zones = listOf(
                ColorZone(-0.5f, 0f,   Color.parseColor("#4488FF")),  // vacuum
                ColorZone(0f,    1.8f, Color.parseColor("#00DD77")),
                ColorZone(1.8f,  2.2f, Color.parseColor("#FFAA00")),
                ColorZone(2.2f,  2.5f, Color.parseColor("#FF2233"))
            ),
            hasData = d.isConnected || d.isDemoMode
        ),
        GaugeConfig(
            label = "COOLANT",
            unit  = "°C",
            min   = 40f, max = 130f,
            value = d.coolantTemperature.coerceIn(40f, 130f),
            zones = listOf(
                ColorZone(40f,  70f,  Color.parseColor("#4488FF")),
                ColorZone(70f,  85f,  Color.parseColor("#FFAA00")),
                ColorZone(85f,  105f, Color.parseColor("#00DD77")),
                ColorZone(105f, 115f, Color.parseColor("#FFAA00")),
                ColorZone(115f, 130f, Color.parseColor("#FF2233"))
            ),
            hasData = d.isConnected || d.isDemoMode
        ),
        GaugeConfig(
            label = "FUEL",
            unit  = "L/100km",
            min   = 0f, max = 25f,
            value = d.fuelConsumption.coerceIn(0f, 25f),
            zones = listOf(
                ColorZone(0f,   6f,  Color.parseColor("#00DD77")),
                ColorZone(6f,   12f, Color.parseColor("#FFAA00")),
                ColorZone(12f,  18f, Color.parseColor("#FF6600")),
                ColorZone(18f,  25f, Color.parseColor("#FF2233"))
            ),
            hasData = d.isConnected || d.isDemoMode
        ),
        GaugeConfig(
            label = "VOLTAGE",
            unit  = "V",
            min   = 11f, max = 15.5f,
            value = d.batteryVoltage.coerceIn(11f, 15.5f),
            zones = listOf(
                ColorZone(11f,   11.8f, Color.parseColor("#FF2233")),
                ColorZone(11.8f, 12.5f, Color.parseColor("#FFAA00")),
                ColorZone(12.5f, 14.8f, Color.parseColor("#00DD77")),
                ColorZone(14.8f, 15.2f, Color.parseColor("#FFAA00")),
                ColorZone(15.2f, 15.5f, Color.parseColor("#FF2233"))
            ),
            hasData = d.isConnected || d.isDemoMode
        )
    )

    // ── Gauge positions ───────────────────────────────────────────────────────

    /**
     * Returns list of (centerX, centerY, radius) for the 6 gauges in a 3×2 grid.
     * Reserves 8 % of height at the bottom for the status bar.
     */
    private fun gaugePositions(): List<Triple<Float, Float, Float>> {
        val w = width.toFloat()
        val usableH = height * 0.92f

        val topY = usableH * 0.28f
        val botY = usableH * 0.76f

        val r = min(w / 3f * 0.42f, usableH * 0.23f)

        return listOf(
            Triple(w * 0.165f, topY, r),   // Oil Pressure
            Triple(w * 0.500f, topY, r),   // Oil Temp
            Triple(w * 0.835f, topY, r),   // Boost
            Triple(w * 0.165f, botY, r),   // Coolant
            Triple(w * 0.500f, botY, r),   // Fuel
            Triple(w * 0.835f, botY, r)    // Voltage
        )
    }

    // ── Single gauge drawing ──────────────────────────────────────────────────

    private fun drawGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float, cfg: GaugeConfig) {
        val arcR       = radius * 0.76f
        val trackWidth = radius * 0.095f
        val faceR      = radius * 0.95f
        val rect       = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        // ── Face
        facePaint.shader = RadialGradient(
            cx, cy, faceR,
            intArrayOf(Color.parseColor("#1E1E32"), Color.parseColor("#0B0B18")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, faceR, facePaint)

        // ── Outer border
        borderPaint.strokeWidth = radius * 0.013f
        canvas.drawCircle(cx, cy, faceR, borderPaint)

        // ── Full track (dark)
        trackPaint.strokeWidth = trackWidth
        canvas.drawArc(rect, ARC_START, ARC_SWEEP, false, trackPaint)

        // ── Coloured zone overlays (dimmed)
        drawZoneTrack(canvas, rect, trackWidth * 0.55f, cfg)

        // ── Value arc (bright + glow)
        val norm        = (cfg.value - cfg.min) / (cfg.max - cfg.min)
        val sweepAngle  = (norm * ARC_SWEEP).coerceIn(0f, ARC_SWEEP)
        if (sweepAngle > 0.5f) {
            val col = cfg.valueColor()
            // Glow layer
            arcPaint.color       = col
            arcPaint.alpha       = 55
            arcPaint.strokeWidth = trackWidth * 2.4f
            canvas.drawArc(rect, ARC_START, sweepAngle, false, arcPaint)
            // Main arc
            arcPaint.alpha       = 255
            arcPaint.strokeWidth = trackWidth
            canvas.drawArc(rect, ARC_START, sweepAngle, false, arcPaint)
        }

        // ── Tick marks
        drawTicks(canvas, cx, cy, arcR, trackWidth)

        // ── Min / max labels
        drawMinMaxLabels(canvas, cx, cy, arcR * 1.12f, cfg)

        // ── Centre value
        valuePaint.textSize = radius * 0.36f
        valuePaint.color    = if (cfg.hasData) Color.WHITE else Color.parseColor("#44445A")
        canvas.drawText(cfg.formatValue(), cx, cy + radius * 0.13f, valuePaint)

        // ── Unit
        unitPaint.textSize = radius * 0.155f
        canvas.drawText(cfg.unit, cx, cy + radius * 0.34f, unitPaint)

        // ── Label
        labelPaint.textSize = radius * 0.145f
        canvas.drawText(cfg.label, cx, cy + radius * 0.86f, labelPaint)

        // ── "~est" badge for estimated oil pressure
        if (cfg.isEstimated) {
            unitPaint.textSize  = radius * 0.115f
            unitPaint.color     = Color.parseColor("#FFAA00")
            canvas.drawText("~est", cx, cy - radius * 0.36f, unitPaint)
            unitPaint.color     = Color.parseColor("#7799BB")  // reset
        }

        // ── Status dot (top-right corner of gauge face)
        dotPaint.color = when {
            !cfg.hasData    -> Color.parseColor("#2A2A3A")
            cfg.isEstimated -> Color.parseColor("#FFAA00")
            else            -> Color.parseColor("#00DD77")
        }
        canvas.drawCircle(cx + faceR * 0.64f, cy - faceR * 0.64f, radius * 0.052f, dotPaint)
    }

    /** Draws dim coloured arcs for each zone as a background reference. */
    private fun drawZoneTrack(canvas: Canvas, rect: RectF, strokeWidth: Float, cfg: GaugeConfig) {
        val range = cfg.max - cfg.min
        for (zone in cfg.zones) {
            val startNorm  = (zone.min - cfg.min) / range
            val endNorm    = (zone.max - cfg.min) / range
            val startAngle = ARC_START + startNorm * ARC_SWEEP
            val sweep      = (endNorm - startNorm) * ARC_SWEEP
            arcPaint.color       = zone.color
            arcPaint.alpha       = 45
            arcPaint.strokeWidth = strokeWidth
            canvas.drawArc(rect, startAngle, sweep, false, arcPaint)
        }
        arcPaint.alpha = 255
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, arcR: Float, trackWidth: Float) {
        for (i in 0..TICK_COUNT) {
            val fraction  = i.toFloat() / TICK_COUNT
            val angleRad  = Math.toRadians((ARC_START + fraction * ARC_SWEEP).toDouble())
            val isMajor   = i % 2 == 0
            val outerR    = arcR - trackWidth * 0.5f
            val tickLen   = if (isMajor) trackWidth * 1.25f else trackWidth * 0.65f
            val innerR    = outerR - tickLen
            val cosA      = cos(angleRad).toFloat()
            val sinA      = sin(angleRad).toFloat()
            tickPaint.color       = if (isMajor) Color.parseColor("#3A5070") else Color.parseColor("#202838")
            tickPaint.strokeWidth = if (isMajor) 2.0f else 1.2f
            canvas.drawLine(cx + outerR * cosA, cy + outerR * sinA,
                            cx + innerR * cosA, cy + innerR * sinA, tickPaint)
        }
    }

    /** Small min/max value text at the arc endpoints. */
    private fun drawMinMaxLabels(canvas: Canvas, cx: Float, cy: Float, labelR: Float, cfg: GaugeConfig) {
        val minAngle = Math.toRadians(ARC_START.toDouble())
        val maxAngle = Math.toRadians((ARC_START + ARC_SWEEP).toDouble())
        unitPaint.textSize = labelR * 0.19f
        val minStr = if (cfg.max - cfg.min >= 50) "%.0f".format(cfg.min) else "%.1f".format(cfg.min)
        val maxStr = if (cfg.max - cfg.min >= 50) "%.0f".format(cfg.max) else "%.1f".format(cfg.max)
        canvas.drawText(minStr,
            cx + labelR * cos(minAngle).toFloat(),
            cy + labelR * sin(minAngle).toFloat() + unitPaint.textSize * 0.4f,
            unitPaint)
        canvas.drawText(maxStr,
            cx + labelR * cos(maxAngle).toFloat(),
            cy + labelR * sin(maxAngle).toFloat() + unitPaint.textSize * 0.4f,
            unitPaint)
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private fun drawStatusBar(canvas: Canvas, data: GaugeData) {
        val barTop = height * 0.924f
        val barH   = height - barTop

        // Dark strip
        val stripPaint = Paint().apply {
            color = Color.parseColor("#0C0C1C")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, barTop, width.toFloat(), height.toFloat(), stripPaint)

        // Separator line
        val linePaint = Paint().apply {
            color       = Color.parseColor("#1A1A30")
            strokeWidth = 1f
        }
        canvas.drawLine(0f, barTop, width.toFloat(), barTop, linePaint)

        val textY = barTop + barH * 0.72f
        statusPaint.textSize = barH * 0.52f

        // Left: connection status
        statusPaint.textAlign = Paint.Align.LEFT
        statusPaint.color     = when {
            data.isDemoMode  -> Color.parseColor("#FFAA00")
            data.isConnected -> Color.parseColor("#00DD77")
            else             -> Color.parseColor("#FF3344")
        }
        val leftText = when {
            data.isDemoMode  -> "  DEMO MODE  –  simulated data"
            data.isConnected -> "  OBD CONNECTED  |  ${data.speed.toInt()} km/h"
            else             -> "  NOT CONNECTED  –  open VW Passat Gauges on phone"
        }
        canvas.drawText(leftText, 0f, textY, statusPaint)

        // Right: app tag
        statusPaint.textAlign = Paint.Align.RIGHT
        statusPaint.color     = Color.parseColor("#333348")
        canvas.drawText("VW PASSAT  ", width.toFloat(), textY, statusPaint)

        // Reset
        statusPaint.textAlign = Paint.Align.CENTER
    }
}
