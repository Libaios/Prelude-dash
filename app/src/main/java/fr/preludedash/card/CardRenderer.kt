package fr.preludedash.card

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import fr.preludedash.ui.DashRenderer

/**
 * Dessine la pochette de la carte musique : très synthétique, lisible d'un coup d'oeil.
 *
 * Android Auto recadre l'image carrée au centre dans une colonne plus haute que large,
 * et pose titre, sous-titre et boutons sur le tiers du bas. Tout le contenu tient donc
 * dans la bande centrale (60 % de la largeur) et dans les deux tiers du haut.
 * Repère de dessin : 512 x 512, mis à l'échelle de [size].
 */
object CardRenderer {
    private const val V = 512f
    private const val SAFE_L = 102f
    private const val SAFE_R = 410f
    private const val CX = 256f

    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun render(s: CardSnapshot, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(DashRenderer.BG)
        c.scale(size / V, size / V)
        draw(c, s)
        return bmp
    }

    fun draw(c: Canvas, s: CardSnapshot) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // En-tête : alerte en rouge, sinon le libellé AFR
        val alert = s.alert
        if (alert != null && s.live) {
            fill.color = DashRenderer.RED
            c.drawRoundRect(RectF(SAFE_L + 6f, 30f, SAFE_R - 6f, 86f), 28f, 28f, fill)
            text.typeface = condensedBold
            text.textSize = 34f
            text.letterSpacing = 0.04f
            text.color = DashRenderer.BG
            c.drawText(fit(text, alert, SAFE_R - SAFE_L - 40f), CX, 70f, text)
        } else {
            text.typeface = medium
            text.textSize = 24f
            text.letterSpacing = 0.16f
            text.color = DashRenderer.MUTED
            c.drawText("AFR", CX, 72f, text)
            fill.color = when {
                s.demo -> DashRenderer.BLUE
                s.live -> DashRenderer.GREEN
                else -> DashRenderer.AMBER
            }
            c.drawCircle(CX - 46f, 64f, 7f, fill)
        }

        // AFR en très gros
        text.typeface = condensedBold
        text.letterSpacing = 0f
        text.textSize = 136f
        text.color = when {
            !s.live -> DashRenderer.MUTED
            s.afrLevel == CardSnapshot.Level.ALERT -> DashRenderer.RED
            else -> DashRenderer.TEXT
        }
        c.drawText(s.afr, CX, 196f, text)

        // Séparateur
        fill.color = DashRenderer.TRACK
        c.drawRect(SAFE_L + 20f, 220f, SAFE_R - 20f, 223f, fill)

        // Eau et éthanol
        val leftX = (SAFE_L + CX) / 2f
        val rightX = (CX + SAFE_R) / 2f
        text.typeface = medium
        text.textSize = 20f
        text.letterSpacing = 0.14f
        text.color = DashRenderer.MUTED
        c.drawText("EAU", leftX, 256f, text)
        c.drawText("ÉTHANOL", rightX, 256f, text)

        text.typeface = condensedBold
        text.letterSpacing = 0f
        text.textSize = 72f
        text.color = if (!s.live) DashRenderer.MUTED else when (s.ectLevel) {
            CardSnapshot.Level.ALERT -> DashRenderer.RED
            CardSnapshot.Level.WARN -> DashRenderer.AMBER
            CardSnapshot.Level.COLD -> DashRenderer.BLUE
            CardSnapshot.Level.NORMAL -> DashRenderer.TEXT
        }
        c.drawText(s.ect + "°", leftX, 322f, text)
        text.color = if (s.live) DashRenderer.TEXT else DashRenderer.MUTED
        c.drawText(s.eth + "%", rightX, 322f, text)
    }

    private fun fit(p: Paint, t: String, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        return s
    }
}
