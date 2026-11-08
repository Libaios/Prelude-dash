package fr.preludedash.ui.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rendu réaliste de la Prelude avec Filament (moteur 3D de Google, sur la carte graphique).
 *
 * La voiture est rendue hors écran sur fond transparent, puis relue en image : la page 3D
 * la pose par-dessus son décor (sol néon, ombre, effets) et ses infos. Un seul moteur est
 * partagé entre l'écran Android Auto et l'aperçu du téléphone, sur son propre fil.
 *
 * Modèle : « Honda Prelude 1997 Free » par Black Snow (Sketchfab), licence CC BY 4.0.
 */
object Car3D {
    private const val TAG = "Car3D"
    const val W = 800
    const val H = 480

    /** Champ vertical identique à la projection du décor (focale 690 sur 480 px). */
    const val FOV_DEG = 38.36

    /** Mise à l'échelle du modèle : 3,01 m dans le fichier, 4,52 m en vrai. */
    private const val MODEL_LEN = 3.0101f
    private const val REAL_LEN = 4.52f
    private const val MODEL_CX = 0.0157f
    private const val MODEL_MIN_Y = 0.0200f

    private val WHEEL_NODES = arrayOf("Cylinder_Material.001_0", "Cylinder_Material.001_0.001")

    class Params(
        val eye: FloatArray,
        val target: FloatArray,
        /** Rotation des roues (radians, positif = marche avant). */
        val wheelAngle: Float,
        /** Couleur d'ambiance (0..1) pour le liseré lumineux. */
        val accent: FloatArray,
    )

    @Volatile
    var failed = false
        private set

    @Volatile
    private var params: Params? = null

    private val lock = Any()
    private var users = 0
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Images : une affichée, une en cours de remplissage
    private var front: Bitmap? = null
    private var back: Bitmap? = null

    // Objets Filament (fil Filament uniquement)
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var materials: UbershaderProvider? = null
    private var asset: FilamentAsset? = null
    private var rimLight = 0
    private var sunLight = 0
    private val wheelInst = IntArray(2)
    private val wheelBase = Array(2) { FloatArray(16) }
    private var wheelsFound = 0
    private var pixels: ByteBuffer? = null
    private var readPending = false
    private var readStartMs = 0L
    private var running = false

    /** Démarre le moteur au premier utilisateur. */
    fun acquire(ctx: Context) {
        synchronized(lock) {
            users++
            if (thread != null || failed) return
            val t = HandlerThread("filament").apply { start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            val app = ctx.applicationContext
            h.post {
                try {
                    setup(app)
                    running = true
                    h.post(loop)
                } catch (e: Throwable) {
                    Log.e(TAG, "Filament indisponible, retour au modèle simple", e)
                    failed = true
                }
            }
        }
    }

    /** Arrête le moteur quand plus personne ne l'utilise. */
    fun release() {
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return
            val h = handler ?: return
            val t = thread
            handler = null
            thread = null
            h.post {
                running = false
                teardown()
                t?.quitSafely()
            }
        }
    }

    /** Position de caméra et état voulus pour la prochaine image. */
    fun submit(p: Params) {
        params = p
    }

    /** Dernière image complète (lignes de bas en haut), ou null au démarrage. */
    fun frame(): Bitmap? = synchronized(lock) { front }

    // -------------------------------------------------------------- mise en place

    private fun setup(ctx: Context) {
        Utils.init()
        Gltfio.init()
        val e = Engine.create()
        engine = e
        renderer = e.createRenderer().apply {
            clearOptions = Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
        }
        val sc = e.createScene()
        scene = sc
        cameraEntity = EntityManager.get().create()
        val cam = e.createCamera(cameraEntity)
        cam.setProjection(FOV_DEG, W.toDouble() / H, 0.1, 100.0, Camera.Fov.VERTICAL)
        cam.setExposure(16f, 1f / 125f, 100f)
        camera = cam
        view = e.createView().apply {
            scene = sc
            camera = cam
            viewport = Viewport(0, 0, W, H)
            blendMode = View.BlendMode.TRANSLUCENT
            antiAliasing = View.AntiAliasing.FXAA
            multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
                enabled = true
                sampleCount = 4
            }
        }
        swapChain = e.createSwapChain(W, H, SwapChainFlags.CONFIG_TRANSPARENT or SwapChainFlags.CONFIG_READABLE)

        // Modèle
        val mp = UbershaderProvider(e)
        materials = mp
        val loader = AssetLoader(e, mp, EntityManager.get())
        assetLoader = loader
        val res = ResourceLoader(e)
        resourceLoader = res
        val a = loader.createAsset(readAsset(ctx, "prelude.glb")) ?: error("modèle illisible")
        res.loadResources(a)
        a.releaseSourceData()
        asset = a
        sc.addEntities(a.entities)
        placeModel(e, a)

        // Roues : on garde leur position d'origine pour les faire tourner sur leur axe
        val tm = e.transformManager
        wheelsFound = 0
        for (name in WHEEL_NODES) {
            val ent = a.getFirstEntityByName(name)
            if (ent == 0) continue
            val inst = tm.getInstance(ent)
            wheelInst[wheelsFound] = inst
            tm.getTransform(inst, wheelBase[wheelsFound])
            wheelsFound++
        }

        // Éclairage : environnement studio pour les reflets, soleil, liseré coloré
        val ibl = KTX1Loader.createIndirectLight(e, readAsset(ctx, "studio_ibl.ktx")).indirectLight
        if (ibl != null) {
            ibl.intensity = 32000f
            sc.indirectLight = ibl
        }
        sunLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.98f, 0.95f)
            .intensity(70000f)
            .direction(0.35f, -0.85f, -0.40f)
            .castShadows(false)
            .build(e, sunLight)
        sc.addEntity(sunLight)
        rimLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.18f, 0.83f, 0.75f)
            .intensity(45000f)
            .direction(-0.55f, -0.15f, 0.82f)
            .castShadows(false)
            .build(e, rimLight)
        sc.addEntity(rimLight)

        pixels = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        synchronized(lock) {
            back = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        }
    }

    /** Remet le modèle à l'échelle réelle, roues au sol, avant vers +x. */
    private fun placeModel(e: Engine, a: FilamentAsset) {
        val s = REAL_LEN / MODEL_LEN
        // Rotation d'un demi-tour (l'avant du fichier est vers -x), échelle, recentrage
        val m = floatArrayOf(
            -s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, -s, 0f,
            s * MODEL_CX, -s * MODEL_MIN_Y, 0f, 1f,
        )
        val tm = e.transformManager
        tm.setTransform(tm.getInstance(a.root), m)
    }

    private fun readAsset(ctx: Context, name: String): ByteBuffer {
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            flip()
        }
    }

    // ------------------------------------------------------------------- boucle

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val start = System.nanoTime()
            try {
                renderOnce()
            } catch (e: Throwable) {
                Log.e(TAG, "erreur de rendu", e)
                failed = true
                return
            }
            val spentMs = (System.nanoTime() - start) / 1_000_000
            handler?.postDelayed(this, (50 - spentMs).coerceIn(5, 50))
        }
    }

    private fun renderOnce() {
        val p = params ?: return
        val e = engine ?: return
        val r = renderer ?: return
        val v = view ?: return
        val sc = swapChain ?: return
        if (readPending) {
            // Sécurité : une relecture qui ne revient pas ne doit pas figer l'image
            if (System.currentTimeMillis() - readStartMs < 1000) return
            readPending = false
        }

        camera?.lookAt(
            p.eye[0].toDouble(), p.eye[1].toDouble(), p.eye[2].toDouble(),
            p.target[0].toDouble(), p.target[1].toDouble(), p.target[2].toDouble(),
            0.0, 1.0, 0.0,
        )
        val lm = e.lightManager
        lm.setColor(lm.getInstance(rimLight), p.accent[0], p.accent[1], p.accent[2])

        // Roues : rotation autour de leur axe (l'axe y de chaque nœud)
        val tm = e.transformManager
        val c = cos(p.wheelAngle)
        val s = sin(p.wheelAngle)
        val rot = floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        for (i in 0 until wheelsFound) {
            tm.setTransform(wheelInst[i], multiply(wheelBase[i], rot))
        }

        if (!r.beginFrame(sc, System.nanoTime())) return
        r.render(v)
        val buf = pixels ?: return
        buf.clear()
        readPending = true
        readStartMs = System.currentTimeMillis()
        r.readPixels(
            0, 0, W, H,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, handler) {
                publish(buf)
            },
        )
        r.endFrame()
        // Attend la fin du rendu : l'image relue arrive alors juste après sur ce fil
        e.flushAndWait()
    }

    private fun publish(buf: ByteBuffer) {
        synchronized(lock) {
            val b = back
            if (b != null) {
                buf.rewind()
                b.copyPixelsFromBuffer(buf)
                back = front ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                front = b
            }
        }
        readPending = false
    }

    /** Produit de deux matrices 4x4 en colonnes : a x b. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    private fun teardown() {
        val e = engine ?: return
        try {
            asset?.let { a ->
                scene?.removeEntities(a.entities)
                assetLoader?.destroyAsset(a)
            }
            resourceLoader?.destroy()
            assetLoader?.destroy()
            materials?.destroyMaterials()
            materials?.destroy()
            scene?.indirectLight?.let { e.destroyIndirectLight(it) }
            e.destroyEntity(sunLight)
            e.destroyEntity(rimLight)
            view?.let { e.destroyView(it) }
            scene?.let { e.destroyScene(it) }
            renderer?.let { e.destroyRenderer(it) }
            swapChain?.let { e.destroySwapChain(it) }
            e.destroyCameraComponent(cameraEntity)
            EntityManager.get().destroy(cameraEntity)
            e.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "nettoyage incomplet", t)
        }
        engine = null
        renderer = null
        scene = null
        view = null
        camera = null
        swapChain = null
        asset = null
        readPending = false
        synchronized(lock) {
            front = null
            back = null
        }
    }
}
