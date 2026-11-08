package fr.preludedash.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Analyseur de spectre en rideau, sur toute la largeur du haut de l'écran : les barres en blocs
 * pendent du bord supérieur et deviennent transparentes en descendant, pour se fondre dans le décor.
 * Ambiance nightcore / lofi : rose, lavande et bleu ciel pastel, halo doux, crêtes qui retombent.
 *
 * Basses sur les côtés (barres longues), aigus au centre (barres courtes) : le milieu de la scène
 * reste dégagé. La ligne lumineuse du bord supérieur sert de barre d'avancement du morceau.
 *
 * Ce n'est pas le vrai son (Spotify ne le partage pas) : animation plausible sur un tempo lofi,
 * active seulement quand un morceau est en lecture.
 */
class SpectrumCurtain(private val bands: Int = 56) {

    private val level = FloatArray(bands)
    private val smooth = FloatArray(bands)
    private val peak = FloatArray(bands)
    private val hold = FloatArray(bands)
    private val freq = FloatArray(bands)   // 0 = basses (bords), 1 = aigus (centre)
    private val env = FloatArray(bands)    // longueur maximale de chaque barre
    private val f1 = FloatArray(bands) { 0.9f + 2.2f * hash(it * 3 + 1) }
    private val f2 = FloatArray(bands) { 2.7f + 4.6f * hash(it * 7 + 2) }
    private val p1 = FloatArray(bands) { 6.28f * hash(it * 11 + 3) }
    private val p2 = FloatArray(bands) { 6.28f * hash(it * 13 + 4) }
    private var lastMs = 0L
    private var time = 0f

    private val seg = Paint()
    private val ghost = Paint()
    private val glow = Paint()
    private val ceilGlow = Paint()
    private val line = Paint()
    private val cap = Paint()
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        color = Color.rgb(255, 236, 248)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.rgb(214, 196, 240)
    }
    private var shaderLen = -1f

    // Palette pastel
    private val pink = Color.rgb(255, 120, 200)
    private val lavender = Color.rgb(185, 140, 255)
    private val sky = Color.rgb(130, 215, 255)

    init {
        for (b in 0 until bands) {
            val u = (b + 0.5f) / bands
            val dc = abs(u - 0.5f) * 2f       // 0 au centre, 1 sur les bords
            freq[b] = 1f - dc
            env[b] = 0.35f + 0.65f * smoothstep(0.1f, 0.65f, dc) - 0.25f * smoothstep(0.85f, 1f, dc)
        }
    }

    /**
     * Dessine le rideau sur [width] pixels à partir de [left], en haut du canevas.
     * [unit] = taille d'un pixel du repère 800 x 480 (mise à l'échelle de l'écran).
     */
    fun draw(
        c: Canvas,
        left: Float,
        width: Float,
        unit: Float,
        playing: Boolean,
        progress: Float?,
        title: String,
        sub: String?,
    ) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        if (playing) time += dt
        update(dt, playing)

        val maxLen = 165f * unit
        shaders(maxLen, unit)
        val pitch = width / bands
        val bw = pitch * 0.66f
        val segH = 7f * unit
        val step = segH + 2.2f * unit
        val nSeg = (maxLen / step).toInt()
        val ghostN = (nSeg * 0.5f).toInt()

        for (b in 0 until bands) {
            val x = left + b * pitch + (pitch - bw) / 2f
            val lit = (smooth[b] * env[b] * nSeg).roundToInt().coerceIn(0, nSeg)
            if (lit > 0) c.drawRect(x - 2.5f * unit, 0f, x + bw + 2.5f * unit, lit * step + 8f * unit, glow)
            for (k in lit until ghostN) c.drawRect(x, k * step, x + bw, k * step + segH, ghost)
            for (k in 0 until lit) c.drawRect(x, k * step, x + bw, k * step + segH, seg)
            val pk = (peak[b] * env[b] * nSeg).toInt()
            if (peak[b] > 0.04f && pk >= lit && pk < nSeg) {
                cap.alpha = (230 * (1f - pk / nSeg.toFloat() * 0.6f)).toInt()
                c.drawRect(x, pk * step, x + bw, pk * step + 2.2f * unit, cap)
            }
        }

        // Bord supérieur lumineux = avancement du morceau
        c.drawRect(left, 0f, left + width, 16f * unit, ceilGlow)
        line.color = Color.argb(70, 255, 140, 210)
        c.drawRect(left, 0f, left + width, 2.2f * unit, line)
        if (progress != null) {
            line.color = Color.argb(235, 255, 170, 225)
            c.drawRect(left, 0f, left + width * progress, 2.2f * unit, line)
        }

        // Titre et artiste, en petit au centre (là où les barres sont courtes)
        titlePaint.textSize = 15.5f * unit
        subPaint.textSize = 13f * unit
        titlePaint.setShadowLayer(5f * unit, 0f, 0f, Color.argb(220, 20, 6, 30))
        subPaint.setShadowLayer(5f * unit, 0f, 0f, Color.argb(220, 20, 6, 30))
        val maxW = width * 0.42f
        val t = fit(titlePaint, title, maxW)
        val s = sub?.takeIf { it.isNotBlank() }?.let { fit(subPaint, it, maxW * 0.7f) }
        val sp = 10f * unit
        val tw = titlePaint.measureText(t)
        val sw = s?.let { subPaint.measureText(it) + sp } ?: 0f
        val x0 = left + (width - tw - sw) / 2f
        val y = 24f * unit
        c.drawText(t, x0, y, titlePaint)
        if (s != null) c.drawText(s, x0 + tw + sp, y, subPaint)
    }

    private fun shaders(len: Float, unit: Float) {
        if (len == shaderLen) return
        shaderLen = len
        val stops = floatArrayOf(0f, 0.5f, 1f)
        seg.shader = LinearGradient(
            0f, 0f, 0f, len,
            intArrayOf(alpha(pink, 215), alpha(lavender, 150), alpha(sky, 60)), stops, Shader.TileMode.CLAMP,
        )
        ghost.shader = LinearGradient(
            0f, 0f, 0f, len * 0.5f,
            intArrayOf(alpha(pink, 30), alpha(lavender, 0)), null, Shader.TileMode.CLAMP,
        )
        glow.shader = LinearGradient(
            0f, 0f, 0f, len,
            intArrayOf(alpha(pink, 60), alpha(lavender, 25), alpha(sky, 0)), stops, Shader.TileMode.CLAMP,
        )
        ceilGlow.shader = LinearGradient(
            0f, 0f, 0f, 16f * unit,
            intArrayOf(alpha(pink, 90), alpha(pink, 0)), null, Shader.TileMode.CLAMP,
        )
        cap.color = Color.rgb(255, 225, 245)
    }

    private fun update(dt: Float, playing: Boolean) {
        // Tempo lofi (90 BPM) : grosse caisse sur 1 et 3, caisse claire sur 2 et 4
        val beat = time * 90f / 60f
        val n = beat.toInt()
        val frac = beat - n
        val kick = if (n % 2 == 0) exp(-frac * 5f) else 0.25f * exp(-frac * 8f)
        val snare = if (n % 2 == 1) exp(-frac * 7f) else 0f
        for (b in 0 until bands) {
            val f = freq[b]
            val target = if (!playing) 0f else {
                val body = 0.92f - 0.25f * f
                val wobble = 0.5f + 0.38f * sin(time * f1[b] + p1[b]) + 0.22f * sin(time * f2[b] + p2[b])
                (body * wobble + kick * (1f - f) * 0.5f + snare * (0.3f + 0.5f * f) * 0.38f).coerceIn(0f, 1f)
            }
            val speed = if (target > level[b]) 14f else 4.5f
            level[b] += (target - level[b]) * min(1f, dt * speed)
        }
        // Lissage entre barres voisines : un rideau souple plutôt qu'un peigne
        for (b in 0 until bands) {
            val l = level[max(0, b - 1)]
            val r = level[min(bands - 1, b + 1)]
            smooth[b] = 0.15f * l + 0.7f * level[b] + 0.15f * r
            if (smooth[b] >= peak[b]) {
                peak[b] = smooth[b]
                hold[b] = 0.5f
            } else if (hold[b] > 0f) {
                hold[b] -= dt
            } else {
                peak[b] = max(0f, peak[b] - dt * 0.5f)
            }
        }
    }

    private fun fit(p: Paint, text: String, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && p.measureText("$t...") > maxWidth) t = t.dropLast(1)
        return "$t..."
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xffff) / 65535f
    }
}
