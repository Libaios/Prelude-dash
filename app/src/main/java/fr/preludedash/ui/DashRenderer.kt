package fr.preludedash.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import fr.preludedash.data.Calibration
import fr.preludedash.data.EngineData
import fr.preludedash.link.DataHub
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dessine le tableau de bord. Le même code sert pour l'écran du téléphone
 * et pour la surface Android Auto, donc les deux affichent exactement la même chose.
 * Tout est dessiné dans un repère virtuel de 800 x 480, mis à l'échelle de l'écran.
 */
class DashRenderer {

    class Snapshot(
        val data: EngineData,
        val state: DataHub.State,
        val message: String,
        val hz: Float,
        val ageMs: Long,
        val page: Int,
        val cal: Calibration,
    )

    private enum class Level { NORMAL, COLD, WARN, ALERT }

    private class Tile(
        val label: String,
        val value: String,
        val unit: String,
        val frac: Float?,
        val level: Level = Level.NORMAL,
    )

    companion object {
        const val VW = 800f
        const val VH = 480f
        const val PAGES = 2

        val BG = Color.rgb(11, 13, 16)
        val PANEL = Color.rgb(21, 26, 32)
        val TRACK = Color.rgb(35, 42, 49)
        val TEXT = Color.rgb(232, 236, 239)
        val MUTED = Color.rgb(124, 135, 146)
        val GREEN = Color.rgb(61, 220, 132)
        val AMBER = Color.rgb(255, 176, 32)
        val RED = Color.rgb(255, 77, 77)
        val BLUE = Color.rgb(77, 163, 255)
        val TEAL = Color.rgb(45, 212, 191)

        fun snapshot(page: Int, cal: Calibration) = Snapshot(
            data = DataHub.data,
            state = DataHub.state,
            message = DataHub.message,
            hz = DataHub.hz,
            ageMs = DataHub.ageMs(),
            page = page,
            cal = cal,
        )
    }

    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = condensedBold
        fontFeatureSettings = "tnum"
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = medium
    }
    private val rect = RectF()

    /** [area] = zone utile (hors barres d'Android Auto). Le fond est peint sur tout le canevas. */
    fun draw(canvas: Canvas, area: Rect, s: Snapshot) {
        canvas.drawColor(BG)
        if (area.width() <= 0 || area.height() <= 0) return
        val scale = min(area.width() / VW, area.height() / VH)
        val ox = area.left + (area.width() - VW * scale) / 2f
        val oy = area.top + (area.height() - VH * scale) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)
        val stale = s.state != DataHub.State.DEMO && s.ageMs > 1500
        drawHeader(canvas, s, stale)
        if (s.page % PAGES == 0) drawMain(canvas, s, stale) else drawDetails(canvas, s, stale)
        canvas.restore()
    }

    // ---------------------------------------------------------------- en-tête

    private fun drawHeader(c: Canvas, s: Snapshot, stale: Boolean) {
        val live = s.state == DataHub.State.CONNECTED && !stale
        fill.color = when {
            s.state == DataHub.State.DEMO -> BLUE
            live -> GREEN
            s.state == DataHub.State.ERROR -> RED
            else -> AMBER
        }
        c.drawCircle(10f, 16f, 6f, fill)

        val text = when {
            s.state == DataHub.State.DEMO -> "Mode démo"
            live -> "ECU connecté   ${s.hz.roundToInt()} Hz"
            s.state == DataHub.State.CONNECTED -> "Connecté, en attente de données"
            else -> s.message
        }
        setLabel(17f, MUTED, 0.02f)
        c.drawText(ellipsize(text, 470f), 24f, 22f, label)

        if (!stale) {
            var right = VW
            if (s.data.mil) right = lamp(c, "MIL", right, AMBER)
            if (s.data.fan) right = lamp(c, "VENTILO", right, BLUE)
            if (s.data.vtec) lamp(c, "VTEC", right, AMBER)
        }
    }

    private fun lamp(c: Canvas, text: String, right: Float, color: Int): Float {
        setLabel(13f, BG, 0.1f)
        val w = label.measureText(text) + 20f
        rect.set(right - w, 5f, right, 27f)
        fill.color = color
        c.drawRoundRect(rect, 11f, 11f, fill)
        c.drawText(text, right - w + 10f, 21f, label)
        return right - w - 8f
    }

    // ---------------------------------------------------------- page principale

    private fun drawMain(c: Canvas, s: Snapshot, stale: Boolean) {
        val d = s.data
        val overRed = !stale && d.rpm >= s.cal.redline

        // Régime
        value.textSize = 92f
        value.color = when {
            stale -> MUTED
            overRed -> RED
            else -> TEXT
        }
        val rpmText = if (stale) "----" else d.rpm.toString()
        c.drawText(rpmText, 0f, 122f, value)
        setLabel(17f, MUTED, 0.1f)
        c.drawText("TR/MIN", value.measureText(rpmText) + 10f, 122f, label)

        // AFR
        val afr = d.afr
        val lean = !stale && afr != null && afr > 15.0f && d.tpsPct > 60
        setLabel(15f, MUTED, 0.1f)
        c.drawText("AFR", 480f, 52f, label)
        value.textSize = 84f
        value.color = when {
            stale || afr == null -> MUTED
            lean -> RED
            else -> TEXT
        }
        val afrText = if (stale || afr == null) "--.-" else fmt1(afr)
        c.drawText(afrText, 480f, 122f, value)
        if (!stale && afr != null) {
            setLabel(20f, MUTED, 0.02f)
            val lam = "λ " + fmt2(afr / 14.7f)
            c.drawText(lam, VW - label.measureText(lam), 52f, label)
        }
        if (lean) {
            setLabel(14f, RED, 0.1f)
            val warn = "PAUVRE EN CHARGE"
            c.drawText(warn, VW - label.measureText(warn), 122f, label)
        }

        drawRpmBar(c, if (stale) 0 else d.rpm, s.cal.redline, 136f)

        val tiles = listOf(
            Tile("EAU", fmt0(d.ectC), "°C", frac(d.ectC, -20f, 120f), ectLevel(d.ectC)),
            Tile("ADMISSION", fmt0(d.iatC), "°C", frac(d.iatC, 0f, 80f), iatLevel(d.iatC)),
            Tile("MAP", d.mapMbar.toString(), "mbar", frac(d.mapMbar.toFloat(), 0f, 1100f)),
            Tile("PAPILLON", d.tpsPct.toString(), "%", d.tpsPct / 100f),
            ethanolTile(d, s.cal),
            Tile("BATTERIE", fmt1(d.batteryV), "V", frac(d.batteryV, 10f, 16f), battLevel(d.batteryV)),
            Tile("AVANCE", fmt0(d.ignDeg), "°", frac(d.ignDeg, -6f, 50f)),
            Tile("INJECTEURS", fmt0(d.dutyPct), "%", d.dutyPct / 100f, dutyLevel(d.dutyPct)),
        )
        grid(c, tiles, top = 200f, rows = 2, cols = 4, stale = stale, valueSize = 54f)
    }

    private fun drawRpmBar(c: Canvas, rpm: Int, redline: Int, top: Float) {
        val maxRpm = ((redline + 1999) / 1000) * 1000 // 7000 -> 8000
        val segs = 48
        val gap = 4f
        val h = 28f
        val sw = (VW - gap * (segs - 1)) / segs
        val step = maxRpm / segs.toFloat()
        for (i in 0 until segs) {
            val segRpm = (i + 1) * step
            val x = i * (sw + gap)
            rect.set(x, top, x + sw, top + h)
            val zone = when {
                segRpm > redline -> RED
                segRpm > redline - 1000 -> AMBER
                else -> TEAL
            }
            val lit = rpm >= segRpm - step / 2f
            fill.color = if (lit) zone else dim(zone)
            c.drawRoundRect(rect, 3f, 3f, fill)
        }
        setLabel(13f, MUTED, 0f)
        for (k in 1 until maxRpm / 1000) {
            val x = k * 1000f / maxRpm * VW
            val t = k.toString()
            c.drawText(t, x - label.measureText(t) / 2f, top + h + 19f, label)
        }
    }

    // ------------------------------------------------------------ page détails

    private fun drawDetails(c: Canvas, s: Snapshot, stale: Boolean) {
        val d = s.data
        val afr = d.afr
        val tiles = listOf(
            Tile("RÉGIME", d.rpm.toString(), "tr/min", frac(d.rpm.toFloat(), 0f, s.cal.redline.toFloat())),
            Tile("AFR", afr?.let { fmt1(it) } ?: "--.-", "", afr?.let { frac(it, 10f, 18f) }),
            Tile("VITESSE", d.vssKmh.toString(), "km/h", null),
            Tile("INJECTION", fmt2(d.injMs), "ms", null),
            Tile("INJECTEURS", fmt0(d.dutyPct), "%", d.dutyPct / 100f, dutyLevel(d.dutyPct)),
            Tile("AVANCE", fmt1(d.ignDeg), "°", frac(d.ignDeg, -6f, 50f)),
            Tile("CORR. COURT", signed(d.stftPct), "%", null),
            Tile("CORR. LONG", signed(d.ltftPct), "%", null),
            Tile("BARO", d.baroMbar.toString(), "mbar", null),
            Tile("SONDE", fmt2(d.o2Volt), "V", d.o2Volt / 5f),
            ethanolTile(d, s.cal),
            Tile("TRAME", d.raw.size.toString(), "octets", null),
        )
        grid(c, tiles, top = 44f, rows = 3, cols = 4, stale = stale, valueSize = 42f)
    }

    // --------------------------------------------------------------- tuiles

    private fun ethanolTile(d: EngineData, cal: Calibration): Tile {
        val e = d.ethanolPct
        return if (cal.ethByte < 0 && e == null) {
            Tile("ÉTHANOL", "--", "à régler", null)
        } else {
            Tile("ÉTHANOL", e?.let { fmt0(it) } ?: "--", "%", e?.let { it / 100f })
        }
    }

    private fun grid(
        c: Canvas,
        tiles: List<Tile>,
        top: Float,
        rows: Int,
        cols: Int,
        stale: Boolean,
        valueSize: Float,
    ) {
        val gap = 12f
        val w = (VW - gap * (cols - 1)) / cols
        val h = (VH - top - gap * (rows - 1)) / rows
        tiles.forEachIndexed { i, t ->
            if (i >= rows * cols) return@forEachIndexed
            val x = (i % cols) * (w + gap)
            val y = top + (i / cols) * (h + gap)
            drawTile(c, t, x, y, w, h, stale, valueSize)
        }
    }

    private fun drawTile(
        c: Canvas,
        t: Tile,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        stale: Boolean,
        valueSize: Float,
    ) {
        rect.set(x, y, x + w, y + h)
        fill.color = PANEL
        c.drawRoundRect(rect, 14f, 14f, fill)

        setLabel(14f, MUTED, 0.12f)
        c.drawText(t.label, x + 14f, y + 26f, label)

        val (valueColor, barColor) = colors(t.level)
        value.textSize = valueSize
        value.color = if (stale) MUTED else valueColor
        val v = if (stale) "--" else t.value
        val baseline = y + h * 0.5f + valueSize * 0.38f
        c.drawText(v, x + 14f, baseline, value)
        if (t.unit.isNotEmpty()) {
            setLabel(16f, MUTED, 0.02f)
            c.drawText(t.unit, x + 14f + value.measureText(v) + 6f, baseline, label)
        }

        val f = t.frac
        if (f != null) {
            val by = y + h - 16f
            rect.set(x + 14f, by, x + w - 14f, by + 5f)
            fill.color = TRACK
            c.drawRoundRect(rect, 3f, 3f, fill)
            if (!stale) {
                rect.right = rect.left + (w - 28f) * f.coerceIn(0f, 1f)
                fill.color = barColor
                c.drawRoundRect(rect, 3f, 3f, fill)
            }
        }
    }

    // ---------------------------------------------------------- seuils couleur

    private fun ectLevel(t: Float) = when {
        t > 105f -> Level.ALERT
        t > 100f -> Level.WARN
        t < 60f -> Level.COLD
        else -> Level.NORMAL
    }

    private fun iatLevel(t: Float) = when {
        t > 60f -> Level.ALERT
        t > 45f -> Level.WARN
        else -> Level.NORMAL
    }

    private fun battLevel(v: Float) = when {
        v < 12.0f || v > 15.2f -> Level.ALERT
        v < 13.0f -> Level.WARN
        else -> Level.NORMAL
    }

    private fun dutyLevel(d: Float) = when {
        d > 90f -> Level.ALERT
        d > 80f -> Level.WARN
        else -> Level.NORMAL
    }

    private fun colors(l: Level): Pair<Int, Int> = when (l) {
        Level.NORMAL -> TEXT to TEAL
        Level.COLD -> BLUE to BLUE
        Level.WARN -> AMBER to AMBER
        Level.ALERT -> RED to RED
    }

    // ---------------------------------------------------------------- outils

    private fun setLabel(size: Float, color: Int, spacing: Float) {
        label.textSize = size
        label.color = color
        label.letterSpacing = spacing
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (label.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && label.measureText("$t...") > maxWidth) t = t.dropLast(1)
        return "$t..."
    }

    private fun dim(color: Int): Int = Color.argb(48, Color.red(color), Color.green(color), Color.blue(color))

    private fun frac(v: Float, lo: Float, hi: Float): Float = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

    private fun fmt0(v: Float) = v.roundToInt().toString()

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun signed(v: Float) = String.format(Locale.US, "%+.1f", v)
}
