package fr.preludedash.car

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fr.preludedash.data.CalStore
import fr.preludedash.link.LinkManager
import fr.preludedash.ui.DashRenderer

/**
 * Écran Android Auto. L'appli se déclare "navigation" : c'est la seule catégorie
 * qui a le droit de dessiner librement sur l'écran de la voiture.
 * Pages : la Prelude en 3D au démarrage, puis les jauges, puis les détails.
 */
class DashScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = DashRenderer()
    private val lock = Any()
    private var surface: Surface? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    @Volatile
    private var page = 0

    @Volatile
    private var drawing = false

    private val renderThread = HandlerThread("dash-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    private val frameTask = object : Runnable {
        override fun run() {
            drawFrame()
            if (drawing) renderHandler.postDelayed(this, 50)
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            synchronized(lock) {
                surface = surfaceContainer.surface
                surfaceW = surfaceContainer.width
                surfaceH = surfaceContainer.height
            }
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            synchronized(lock) { this@DashScreen.visibleArea = Rect(visibleArea) }
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            synchronized(lock) { this@DashScreen.stableArea = Rect(stableArea) }
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            synchronized(lock) { surface = null }
        }

        // Tap sur l'écran : page suivante (Android Auto récent)
        override fun onClick(x: Float, y: Float) {
            nextPage()
        }
    }

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                    LinkManager.acquire(carContext, "car")
                }
                Lifecycle.Event.ON_START -> {
                    drawing = true
                    renderHandler.removeCallbacks(frameTask)
                    renderHandler.post(frameTask)
                }
                Lifecycle.Event.ON_STOP -> {
                    drawing = false
                    renderHandler.removeCallbacks(frameTask)
                }
                Lifecycle.Event.ON_DESTROY -> {
                    LinkManager.release("car")
                    renderThread.quitSafely()
                }
                else -> Unit
            }
        })
    }

    override fun onGetTemplate(): Template {
        val pageAction = Action.Builder()
            .setTitle(DashRenderer.nextPageName(page))
            .setOnClickListener { nextPage() }
            .build()
        val strip = ActionStrip.Builder().addAction(pageAction).build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    private fun nextPage() {
        page = (page + 1) % DashRenderer.PAGES
        invalidate()
    }

    private fun drawFrame() {
        synchronized(lock) {
            val s = surface ?: return
            if (!s.isValid) return
            val canvas = try {
                s.lockCanvas(null)
            } catch (e: Exception) {
                null
            } ?: return
            try {
                val area = stableArea ?: visibleArea ?: Rect(0, 0, surfaceW, surfaceH)
                renderer.draw(canvas, area, DashRenderer.snapshot(page, CalStore.get(carContext)))
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        }
    }
}
