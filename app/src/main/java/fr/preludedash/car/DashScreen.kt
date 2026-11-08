package fr.preludedash.car

import android.graphics.Rect
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
import fr.preludedash.link.LinkManager
import fr.preludedash.ui.DashRenderer
import fr.preludedash.ui.stage.DashSurface

/**
 * Écran Android Auto. L'appli se déclare "navigation" : c'est la seule catégorie
 * qui a le droit de dessiner librement sur l'écran de la voiture.
 * Pages : la Prelude en 3D au démarrage, puis les jauges, puis les détails.
 * Le dessin lui-même (3D + tableau de bord) est fait par [DashSurface].
 */
class DashScreen(carContext: CarContext) : Screen(carContext) {

    private val dash = DashSurface(carContext, "car")
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    @Volatile
    private var page = 0

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val s = surfaceContainer.surface ?: return
            dash.attach(s, surfaceContainer.width, surfaceContainer.height)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this@DashScreen.visibleArea = Rect(visibleArea)
            dash.setArea(stableArea ?: this@DashScreen.visibleArea)
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            this@DashScreen.stableArea = Rect(stableArea)
            dash.setArea(this@DashScreen.stableArea)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            dash.detach()
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
                Lifecycle.Event.ON_START -> dash.start()
                Lifecycle.Event.ON_STOP -> dash.stop()
                Lifecycle.Event.ON_DESTROY -> {
                    LinkManager.release("car")
                    dash.release()
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
        dash.page = page
        invalidate()
    }
}
