package fr.preludedash.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Relais vers l'appli de musique en cours (Spotify de préférence) : lecture, pause,
 * piste suivante/précédente, et titre en cours pour l'afficher sur la carte.
 * Nécessite l'accès aux notifications pour Prelude Dash.
 */
object MusicRelay {
    private const val SPOTIFY = "com.spotify.music"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    @Volatile
    var title: String? = null
        private set

    @Volatile
    var artist: String? = null
        private set

    @Volatile
    var playing: Boolean = false
        private set

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            readMeta(metadata)
            fire()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playing = state?.state == PlaybackState.STATE_PLAYING
            fire()
        }

        override fun onSessionDestroyed() {
            refresh()
        }
    }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, MusicListenerService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Démarre le suivi si l'accès est donné. Sans effet sinon (la carte marche quand même). */
    fun start(ctx: Context) {
        main.post {
            val c = ctx.applicationContext
            app = c
            if (manager != null || !hasAccess(c)) {
                refresh()
                return@post
            }
            try {
                val m = c.getSystemService(MediaSessionManager::class.java)
                val comp = ComponentName(c, MusicListenerService::class.java)
                m.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
                manager = m
                pick(m.getActiveSessions(comp))
            } catch (_: SecurityException) {
                manager = null
            }
        }
    }

    fun refresh() {
        main.post {
            val c = app ?: return@post
            val m = manager
            if (m == null) {
                if (hasAccess(c)) start(c)
                return@post
            }
            try {
                pick(m.getActiveSessions(ComponentName(c, MusicListenerService::class.java)))
            } catch (_: SecurityException) {
            }
        }
    }

    private fun pick(list: List<MediaController>) {
        val own = app?.packageName
        val others = list.filter { it.packageName != own }
        val chosen = others.firstOrNull { it.packageName == SPOTIFY } ?: others.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        controller = chosen
        if (chosen != null) {
            chosen.registerCallback(controllerCallback, main)
            readMeta(chosen.metadata)
            playing = chosen.playbackState?.state == PlaybackState.STATE_PLAYING
        } else {
            title = null
            artist = null
            playing = false
        }
        fire()
    }

    private fun readMeta(m: MediaMetadata?) {
        title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
    }

    /** Ligne à afficher sur la carte, ou null s'il n'y a pas de musique. */
    fun line(): String? {
        val t = title ?: return null
        val icon = if (playing) "▶" else "❚❚"
        val a = artist
        return if (a.isNullOrBlank()) "$icon  $t" else "$icon  $t - $a"
    }

    fun toggle() = main.post {
        val c = controller ?: return@post
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun play() = main.post { controller?.transportControls?.play() }

    fun next() = main.post { controller?.transportControls?.skipToNext() }

    fun previous() = main.post { controller?.transportControls?.skipToPrevious() }

    fun addListener(l: Runnable) {
        listeners.add(l)
    }

    fun removeListener(l: Runnable) {
        listeners.remove(l)
    }

    private fun fire() {
        for (l in listeners) {
            try {
                l.run()
            } catch (_: Exception) {
            }
        }
    }
}
