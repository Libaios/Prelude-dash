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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dessine le tableau de bord plein écran. Le même code sert pour l'aperçu du téléphone
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
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
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
            if (s.data.fan) lamp(c, "VENTILO", right, BLUE)
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
        drawTach(c, s, stale, cx = 192f, cy = 268f, r = 168f)

        // AFR
        val left = 392f
        val afr = d.afr
        val lean = !stale && afr != null && afr > 15.0f && d.tpsPct > 60
        setLabel(15f, MUTED, 0.12f)
        c.drawText("AFR", left, 60f, label)
        value.textSize = 92f
        value.color = when {
            stale || afr == null -> MUTED
            lean -> RED
            else -> TEXT
        }
        val afrText = if (stale || afr == null) "--.-" else fmt1(afr)
        c.drawText(afrText, left, 140f, value)
        if (!stale && afr != null) {
            setLabel(22f, MUTED, 0.02f)
            val lam = "λ " + fmt2(afr / 14.7f)
            c.drawText(lam, VW - label.measureText(lam), 60f, label)
        }
        if (lean) {
            setLabel(14f, RED, 0.1f)
            val warn = "PAUVRE EN CHARGE"
            c.drawText(warn, VW - label.measureText(warn), 140f, label)
        }

        // Échelle AFR 10 -> 18 avec repère
        val sy = 160f
        rect.set(left, sy, VW, sy + 6f)
        fill.color = TRACK
        c.drawRoundRect(rect, 3f, 3f, fill)
        val stoX = left + (14.7f - 10f) / 8f * (VW - left)
        fill.color = MUTED
        c.drawRect(stoX - 1f, sy - 4f, stoX + 1f, sy + 10f, fill)
        if (!stale && afr != null) {
            val mx = left + ((afr - 10f) / 8f).coerceIn(0f, 1f) * (VW - left)
            fill.color = if (lean) RED else TEAL
            c.drawCircle(mx, sy + 3f, 8f, fill)
        }
        setLabel(12f, MUTED, 0f)
        c.drawText("10", left, sy + 26f, label)
        c.drawText("14.7", stoX - label.measureText("14.7") / 2f, sy + 26f, label)
        c.drawText("18", VW - label.measureText("18"), sy + 26f, label)

        val tiles = listOf(
            Tile("EAU", fmt0(d.ectC), "°C", frac(d.ectC, -20f, 120f), ectLevel(d.ectC)),
            Tile("ADMISSION", fmt0(d.iatC), "°C", frac(d.iatC, 0f, 80f), iatLevel(d.iatC)),
            ethanolTile(d, s.cal),
            Tile("MAP", d.mapMbar.toString(), "mbar", frac(d.mapMbar.toFloat(), 0f, 1100f)),
            Tile("BATTERIE", fmt1(d.batteryV), "V", frac(d.batteryV, 10f, 16f), battLevel(d.batteryV)),
            Tile("AVANCE", fmt0(d.ignDeg), "°", frac(d.ignDeg, -6f, 50f)),
        )
        grid(c, tiles, left = left, top = 202f, width = VW - left, rows = 2, cols = 3,
            stale = stale, valueSize = 46f, compact = true)
    }

    /** Compte-tours en arc de 270°, ouvert en bas, comme un vrai cadran. */
    private fun drawTach(c: Canvas, s: Snapshot, stale: Boolean, cx: Float, cy: Float, r: Float) {
        val redline = s.cal.redline
        val maxRpm = ((redline + 1999) / 1000) * 1000 // 7000 -> 8000
        val rpm = if (stale) 0 else s.data.rpm
        val start = 135f
        val sweep = 270f
        fun angle(v: Float) = start + sweep * (v / maxRpm).coerceIn(0f, 1f)

        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = 20f
        stroke.strokeCap = Paint.Cap.BUTT
        // Piste, puis zone rouge en sourdine
        stroke.color = TRACK
        c.drawArc(rect, start, sweep, false, stroke)
        stroke.color = dim(RED, 110)
        c.drawArc(rect, angle(redline.toFloat()), start + sweep - angle(redline.toFloat()), false, stroke)

        // Aiguille "remplie" jusqu'au régime actuel
        if (rpm > 0) {
            stroke.color = when {
                rpm >= redline -> RED
                rpm >= redline - 1000 -> AMBER
                else -> TEAL
            }
            c.drawArc(rect, start, angle(rpm.toFloat()) - start, false, stroke)
            val a = Math.toRadians(angle(rpm.toFloat()).toDouble())
            fill.color = TEXT
            c.drawCircle(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), 7f, fill)
        }

        // Graduations
        stroke.strokeCap = Paint.Cap.ROUND
        setLabel(19f, MUTED, 0f)
        label.textAlign = Paint.Align.CENTER
        for (h in 0..maxRpm / 500) {
            val v = h * 500f
            val a = Math.toRadians(angle(v).toDouble())
            val major = h % 2 == 0
            val r1 = r - 18f
            val r2 = if (major) r - 34f else r - 26f
            stroke.strokeWidth = if (major) 3f else 2f
            stroke.color = if (v >= redline) RED else MUTED
            c.drawLine(
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), stroke,
            )
            if (major) {
                val rt = r - 52f
                label.color = if (v >= redline) RED else MUTED
                c.drawText((h / 2).toString(), cx + (rt * cos(a)).toFloat(), cy + (rt * sin(a)).toFloat() + 7f, label)
            }
        }

        // Régime en chiffres
        value.textAlign = Paint.Align.CENTER
        value.textSize = 74f
        value.color = when {
            stale -> MUTED
            rpm >= redline -> RED
            else -> TEXT
        }
        c.drawText(if (stale) "----" else rpm.toString(), cx, cy + 22f, value)
        setLabel(14f, MUTED, 0.14f)
        label.textAlign = Paint.Align.CENTER
        c.drawText("TR/MIN", cx, cy + 48f, label)

        // VTEC au-dessus
        if (!stale && s.data.vtec) {
            setLabel(15f, BG, 0.14f)
            label.textAlign = Paint.Align.CENTER
            rect.set(cx - 38f, cy - 76f, cx + 38f, cy - 50f)
            fill.color = AMBER
            c.drawRoundRect(rect, 13f, 13f, fill)
            c.drawText("VTEC", cx, cy - 57f, label)
        }

        // Vitesse dans l'ouverture du cadran
        value.textSize = 34f
        value.color = if (stale) MUTED else TEXT
        c.drawText(if (stale) "--" else s.data.vssKmh.toString(), cx, cy + r - 14f, value)
        setLabel(13f, MUTED, 0.1f)
        label.textAlign = Paint.Align.CENTER
        c.drawText("KM/H", cx, cy + r + 4f, label)

        value.textAlign = Paint.Align.LEFT
        label.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------ page détails

    private fun drawDetails(c: Canvas, s: Snapshot, stale: Boolean) {
        val d = s.data
        val tiles = listOf(
            Tile("VITESSE", d.vssKmh.toString(), "km/h", null),
            Tile("PAPILLON", d.tpsPct.toString(), "%", d.tpsPct / 100f),
            Tile("INJECTION", fmt2(d.injMs), "ms", null),
            Tile("INJECTEURS", fmt0(d.dutyPct), "%", d.dutyPct / 100f, dutyLevel(d.dutyPct)),
            Tile("CORR. COURT", signed(d.stftPct), "%", null),
            Tile("CORR. LONG", signed(d.ltftPct), "%", null),
            Tile("BARO", d.baroMbar.toString(), "mbar", null),
            Tile("SONDE O2", fmt2(d.o2Volt), "V", d.o2Volt / 5f),
        )
        grid(c, tiles, left = 0f, top = 44f, width = VW, rows = 2, cols = 4,
            stale = stale, valueSize = 60f, compact = false)
    }

    // --------------------------------------------------------------- tuiles

    private fun ethanolTile(d: EngineData, cal: Calibration): Tile {
        val e = d.ethanolPct
        return if (cal.ethByte < 0 && e == null) {
            Tile("ÉTHANOL", "--", "", null)
        } else {
            Tile("ÉTHANOL", e?.let { fmt0(it) } ?: "--", "%", e?.let { it / 100f })
        }
    }

    private fun grid(
        c: Canvas,
        tiles: List<Tile>,
        left: Float,
        top: Float,
        width: Float,
        rows: Int,
        cols: Int,
        stale: Boolean,
        valueSize: Float,
        compact: Boolean,
    ) {
        val gap = 12f
        val w = (width - gap * (cols - 1)) / cols
        val h = (VH - top - gap * (rows - 1)) / rows
        tiles.forEachIndexed { i, t ->
            if (i >= rows * cols) return@forEachIndexed
            val x = left + (i % cols) * (w + gap)
            val y = top + (i / cols) * (h + gap)
            drawTile(c, t, x, y, w, h, stale, valueSize, compact)
        }
    }

    /** [compact] : l'unité passe en haut à droite, pour les tuiles étroites. */
    private fun drawTile(
        c: Canvas,
        t: Tile,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        stale: Boolean,
        valueSize: Float,
        compact: Boolean,
    ) {
        rect.set(x, y, x + w, y + h)
        fill.color = PANEL
        c.drawRoundRect(rect, 14f, 14f, fill)

        setLabel(13f, MUTED, 0.1f)
        c.drawText(ellipsize(t.label, w - 28f - if (compact) 34f else 0f), x + 14f, y + 25f, label)

        val (valueColor, barColor) = colors(t.level)
        value.textSize = valueSize
        value.color = if (stale) MUTED else valueColor
        val v = if (stale) "--" else t.value
        val baseline = y + h * 0.5f + valueSize * 0.38f
        c.drawText(v, x + 14f, baseline, value)
        if (t.unit.isNotEmpty()) {
            if (compact) {
                setLabel(13f, MUTED, 0.02f)
                c.drawText(t.unit, x + w - 14f - label.measureText(t.unit), y + 25f, label)
            } else {
                setLabel(16f, MUTED, 0.02f)
                c.drawText(t.unit, x + 14f + value.measureText(v) + 6f, baseline, label)
            }
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
        label.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (label.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && label.measureText("$t...") > maxWidth) t = t.dropLast(1)
        return "$t..."
    }

    private fun dim(color: Int, alpha: Int = 48): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun frac(v: Float, lo: Float, hi: Float): Float = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

    private fun fmt0(v: Float) = v.roundToInt().toString()

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun signed(v: Float) = String.format(Locale.US, "%+.1f", v)
}
