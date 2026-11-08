package fr.preludedash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import fr.preludedash.data.CalStore
import fr.preludedash.ui.scene.Car3D
import kotlin.math.min

/** Aperçu du tableau de bord sur le téléphone (même rendu que dans la voiture). */
class DashView(context: Context) : View(context) {
    var page = 0

    private val renderer = DashRenderer()
    private val area = Rect()
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 50)
        }
    }

    init {
        // Rendu logiciel : la scène 3D dessine des triangles colorés
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setOnClickListener { page = (page + 1) % DashRenderer.PAGES }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Car3D.acquire(context)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        Car3D.release()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        val h = min((w * DashRenderer.VH / DashRenderer.VW).toInt(), maxH)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        area.set(0, 0, width, height)
        renderer.draw(canvas, area, DashRenderer.snapshot(page, CalStore.get(context)))
    }
}
