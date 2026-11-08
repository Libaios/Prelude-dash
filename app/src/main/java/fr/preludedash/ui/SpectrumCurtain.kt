package fr.preludedash.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import fr.preludedash.shared.Palette
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Égaliseur en rideau en haut de l'écran, à la suite de la pochette : de fines lignes qui pendent
 * du bord supérieur et s'effacent en descendant, pour se fondre dans le décor.
 * Ambiance nightcore / lofi : rose, lavande et bleu ciel pastel, petites crêtes qui retombent.
 *
 * Basses à gauche (lignes longues), aigus à droite (lignes courtes), comme sur une chaîne hi-fi.
 * La ligne turquoise du bord supérieur montre l'avancement du morceau.
 *
 * Ce n'est pas le vrai son (Spotify ne le partage pas) : animation plausible sur un tempo lofi,
 * active seulement quand un morceau est en lecture.
 */
class SpectrumCurtain(private val bands: Int = 80) {

    private val level = FloatArray(bands)
    private val smooth = FloatArray(bands)
    private val peak = FloatArray(bands)
    private val hold = FloatArray(bands)
    private val freq = FloatArray(bands)   // 0 = basses (à gauche), 1 = aigus (à droite)
    private val env = FloatArray(bands)    // longueur maximale de chaque ligne
    private val f1 = FloatArray(bands) { 0.9f + 2.2f * hash(it * 3 + 1) }
    private val f2 = FloatArray(bands) { 2.7f + 4.6f * hash(it * 7 + 2) }
    private val p1 = FloatArray(bands) { 6.28f * hash(it * 11 + 3) }
    private val p2 = FloatArray(bands) { 6.28f * hash(it * 13 + 4) }
    private var lastMs = 0L
    private var time = 0f

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ceilGlow = Paint()
    private val line = Paint()
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderLen = -1f

    // Palette pastel
    private val pink = Color.rgb(255, 120, 200)
    private val lavender = Color.rgb(185, 140, 255)
    private val sky = Color.rgb(130, 215, 255)
    private val teal = Palette.TEAL

    init {
        for (b in 0 until bands) {
            // Comme un vrai analyseur : basses à gauche (juste après la pochette), aigus à droite,
            // avec une entrée en douceur près du titre
            val u = (b + 0.5f) / bands
            freq[b] = u
            env[b] = (1f - 0.5f * u) * (0.35f + 0.65f * smoothstep(0f, 0.08f, u))
        }
    }

    /**
     * Dessine le rideau sur [width] pixels à partir de [left], en haut du canevas.
     * [unit] = taille d'un pixel du repère 800 x 480 (mise à l'échelle de l'écran).
     */
    fun draw(c: Canvas, left: Float, width: Float, unit: Float, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        if (playing) time += dt
        update(dt, playing)

        val maxLen = 165f * unit
        shaders(maxLen, unit)
        val pitch = width / bands
        val lw = 2f * unit           // ligne fine
        val gw = 6f * unit           // halo autour
        for (b in 0 until bands) {
            val cx = left + (b + 0.5f) * pitch
            val len = smooth[b] * env[b] * maxLen
            if (len > 1f) {
                c.drawRoundRect(cx - gw / 2f, 0f, cx + gw / 2f, len + 3f * unit, gw / 2f, gw / 2f, glow)
                c.drawRoundRect(cx - lw / 2f, 0f, cx + lw / 2f, len, lw / 2f, lw / 2f, bar)
            }
            val pk = peak[b] * env[b] * maxLen
            if (peak[b] > 0.04f && pk > len + 2f * unit) {
                cap.alpha = (220 * (1f - pk / maxLen * 0.7f)).toInt().coerceIn(0, 255)
                c.drawCircle(cx, pk, 1.6f * unit, cap)
            }
        }

    }

    /** Bord supérieur turquoise sur [width] pixels = avancement du morceau. */
    fun drawProgress(c: Canvas, left: Float, width: Float, unit: Float, progress: Float?) {
        shaders(165f * unit, unit)
        c.drawRect(left, 0f, left + width, 14f * unit, ceilGlow)
        line.color = alpha(teal, 70)
        c.drawRect(left, 0f, left + width, 2.4f * unit, line)
        if (progress != null) {
            line.color = alpha(teal, 240)
            c.drawRect(left, 0f, left + width * progress, 2.4f * unit, line)
        }
    }

    private fun shaders(len: Float, unit: Float) {
        if (len == shaderLen) return
        shaderLen = len
        val stops = floatArrayOf(0f, 0.45f, 1f)
        bar.shader = LinearGradient(
            0f, 0f, 0f, len,
            intArrayOf(alpha(pink, 190), alpha(lavender, 120), alpha(sky, 20)), stops, Shader.TileMode.CLAMP,
        )
        glow.shader = LinearGradient(
            0f, 0f, 0f, len,
            intArrayOf(alpha(pink, 45), alpha(lavender, 18), alpha(sky, 0)), stops, Shader.TileMode.CLAMP,
        )
        ceilGlow.shader = LinearGradient(
            0f, 0f, 0f, 14f * unit,
            intArrayOf(alpha(teal, 80), alpha(teal, 0)), null, Shader.TileMode.CLAMP,
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
        // Lissage entre lignes voisines : un rideau souple plutôt qu'un peigne
        for (b in 0 until bands) {
            val l = level[max(0, b - 1)]
            val r = level[min(bands - 1, b + 1)]
            smooth[b] = 0.2f * l + 0.6f * level[b] + 0.2f * r
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
