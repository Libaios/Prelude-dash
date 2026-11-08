package fr.preludedash.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Dessine la pochette de Prelude Carte. Une seule image sert à deux affichages :
 *
 * - **Colonne à côté de Waze** : Android Auto agrandit l'image à toute la hauteur de la
 *   colonne et n'en garde que la bande centrale (x de 123 à 389 sur 512, mesuré sur un
 *   écran 1024x600), assombrie, avec titre et boutons sur le bas. On y met l'essentiel :
 *   AFR, eau, éthanol, alerte.
 * - **Écran musique plein écran** : l'image carrée est affichée en entier. Les deux bandes
 *   latérales, invisibles dans la colonne, ajoutent régime, MAP, admission et batterie.
 *
 * Couleurs poussées au maximum de contraste à cause de l'assombrissement d'Android Auto.
 * Repère de dessin : 512 x 512, mis à l'échelle de [size].
 */
object CardRenderer {
    private const val V = 512f

    // Bande centrale visible dans la colonne (avec une petite marge)
    private const val BAND_L = 130f
    private const val BAND_R = 382f
    private const val CX = 256f

    // Bandes latérales, visibles seulement en plein écran
    private const val LEFT_CX = 64f
    private const val RIGHT_CX = 448f

    private val WHITE = Color.WHITE
    private val LABEL = Color.rgb(176, 184, 192)
    private val BLACK = Color.rgb(4, 5, 7)
    private val SIDE_BG = Color.rgb(20, 24, 30)

    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun render(s: CardSnapshot, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / V, size / V)
        draw(c, s)
        return bmp
    }

    fun draw(c: Canvas, s: CardSnapshot) {
        c.drawColor(BLACK)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        drawCenter(c, s, text, fill)
        drawSides(c, s, text, fill)
    }

    private fun drawCenter(c: Canvas, s: CardSnapshot, text: Paint, fill: Paint) {
        // En-tête : alerte en rouge, sinon le libellé AFR avec le point d'état
        val alert = s.alert
        if (alert != null && s.live) {
            fill.color = Palette.RED
            c.drawRoundRect(RectF(BAND_L, 40f, BAND_R, 92f), 26f, 26f, fill)
            text.typeface = condensedBold
            text.textSize = 32f
            text.letterSpacing = 0.02f
            text.color = WHITE
            c.drawText(fit(text, alert, BAND_R - BAND_L - 24f), CX, 77f, text)
        } else {
            text.typeface = medium
            text.textSize = 26f
            text.letterSpacing = 0.18f
            text.color = LABEL
            c.drawText("AFR", CX + 10f, 78f, text)
            fill.color = when {
                s.demo -> Palette.BLUE
                s.live -> Palette.GREEN
                else -> Palette.AMBER
            }
            c.drawCircle(CX - 44f, 69f, 8f, fill)
        }

        // AFR en très gros
        text.typeface = condensedBold
        text.letterSpacing = -0.02f
        text.textSize = 128f
        text.color = when {
            !s.live -> LABEL
            s.afrLevel == CardSnapshot.Level.ALERT -> Palette.RED
            else -> WHITE
        }
        c.drawText(fit(text, s.afr, BAND_R - BAND_L), CX, 200f, text)

        // Séparateur
        fill.color = Color.rgb(60, 68, 78)
        c.drawRect(BAND_L + 10f, 224f, BAND_R - 10f, 227f, fill)

        // Eau et éthanol, chacun dans sa moitié de bande
        val leftX = (BAND_L + CX) / 2f
        val rightX = (CX + BAND_R) / 2f
        text.typeface = medium
        text.textSize = 20f
        text.letterSpacing = 0.12f
        text.color = LABEL
        c.drawText("EAU", leftX, 260f, text)
        c.drawText("E85", rightX, 260f, text)

        text.typeface = condensedBold
        text.letterSpacing = -0.02f
        text.textSize = 64f
        text.color = if (!s.live) LABEL else when (s.ectLevel) {
            CardSnapshot.Level.ALERT -> Palette.RED
            CardSnapshot.Level.WARN -> Palette.AMBER
            CardSnapshot.Level.COLD -> Palette.BLUE
            CardSnapshot.Level.NORMAL -> WHITE
        }
        c.drawText(fit(text, s.ect + "°", 118f), leftX, 322f, text)
        text.color = if (s.live) WHITE else LABEL
        c.drawText(fit(text, s.eth + "%", 118f), rightX, 322f, text)
    }

    /** Bandes latérales : visibles uniquement quand l'image est affichée en entier. */
    private fun drawSides(c: Canvas, s: CardSnapshot, text: Paint, fill: Paint) {
        fill.color = SIDE_BG
        c.drawRoundRect(RectF(8f, 40f, 120f, 472f), 18f, 18f, fill)
        c.drawRoundRect(RectF(392f, 40f, 504f, 472f), 18f, 18f, fill)

        side(c, text, LEFT_CX, 100f, "TR/MIN", s.rpm, s.live)
        side(c, text, LEFT_CX, 300f, "MAP", s.map, s.live)
        side(c, text, RIGHT_CX, 100f, "ADM.", s.iat, s.live)
        side(c, text, RIGHT_CX, 300f, "BATT", s.batt, s.live)
    }

    private fun side(c: Canvas, text: Paint, cx: Float, top: Float, label: String, value: String, live: Boolean) {
        text.typeface = medium
        text.textSize = 18f
        text.letterSpacing = 0.1f
        text.color = LABEL
        c.drawText(label, cx, top, text)
        text.typeface = condensedBold
        text.letterSpacing = -0.02f
        text.textSize = 44f
        text.color = if (live) WHITE else LABEL
        c.drawText(fit(text, value, 100f), cx, top + 56f, text)
    }

    private fun fit(p: Paint, t: String, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        val original = p.textSize
        while (p.textSize > 12f && p.measureText(t) > maxW) p.textSize -= 2f
        if (p.measureText(t) <= maxW) return t
        p.textSize = original
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        return s
    }
}
