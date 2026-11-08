package fr.preludedash.carte

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.media.MediaBrowserService
import android.util.Log
import fr.preludedash.shared.CardSnapshot
import fr.preludedash.shared.ShareContract

/**
 * La carte "musique" d'Android Auto, celle qui se place à côté de Waze.
 *
 * Ce n'est pas un lecteur : la session sert de support à la carte, ne joue aucun son et ne
 * prend jamais la main sur l'audio. Les données viennent de Prelude Dash (qui parle à l'ECU)
 * 4 fois par seconde, et les boutons sont relayés à Spotify par Prelude Dash.
 *
 * La session reste toujours en lecture : Android Auto garde ainsi la carte affichée, et le
 * bouton visible est toujours "pause", que l'on traite comme lecture/pause de Spotify.
 */
class CardService : MediaBrowserService() {

    companion object {
        private const val ROOT_ID = "root"
        private const val ITEM_RESUME = "resume"
        private const val TICK_MS = 250L
    }

    private lateinit var session: MediaSession
    private val thread = HandlerThread("card").apply { start() }
    private val handler = Handler(thread.looper)
    private var frameId = 0
    private var last: CardSnapshot? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                publishIfChanged()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "prelude-carte")
        session.setCallback(object : MediaSession.Callback() {
            // Envoyé quand Android Auto sélectionne la carte : on ne relance rien
            override fun onPlay() = publishState()

            // Le bouton affiché (pause) et celui du volant : lecture/pause de Spotify
            override fun onPause() = music(ShareContract.MUSIC_TOGGLE)

            override fun onStop() = publishState()

            override fun onSkipToNext() = music(ShareContract.MUSIC_NEXT)

            override fun onSkipToPrevious() = music(ShareContract.MUSIC_PREVIOUS)

            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                if (mediaId == ITEM_RESUME) music(ShareContract.MUSIC_PLAY) else publishState()
            }
        })
        session.isActive = true
        sessionToken = session.sessionToken
        publishState()
        handler.post(tick)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i("PreludeCarte", "connexion de $clientPackageName, indices=${rootHints?.keySet()}")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        Log.i("PreludeCarte", "liste demandée ($parentId)")
        // Un seul élément, pour que l'écran de l'appli musique ne soit pas vide
        val desc = MediaDescription.Builder()
            .setMediaId(ITEM_RESUME)
            .setTitle("Reprendre la musique")
            .setSubtitle("Relance l'appli de musique en cours")
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    /** Demande la carte à Prelude Dash ; message d'aide si elle ne répond pas. */
    private fun fetch(): CardSnapshot = try {
        val b = contentResolver.call(ShareContract.URI, ShareContract.M_CARD, null, null)
        if (b != null) CardSnapshot.fromBundle(b)
        else CardSnapshot.offline("Prelude Dash ne répond pas", "Réponse vide (version ${dashVersion()})")
    } catch (e: SecurityException) {
        CardSnapshot.offline("Accès refusé", "Réinstalle Prelude Carte après Prelude Dash")
    } catch (e: IllegalArgumentException) {
        // Fournisseur inconnu : Prelude Dash absent ou trop ancien (canal ajouté en 1.0.6)
        CardSnapshot.offline("Prelude Dash à mettre à jour", "Installée : ${dashVersion()}, il faut 1.0.6 ou plus")
    } catch (e: Exception) {
        CardSnapshot.offline("Erreur ${e.javaClass.simpleName}", (e.message ?: "sans détail").take(80))
    }

    /** Version de Prelude Dash installée, pour le diagnostic affiché sur la carte. */
    private fun dashVersion(): String = try {
        packageManager.getPackageInfo("fr.preludedash", 0).versionName ?: "?"
    } catch (_: Exception) {
        "pas installée"
    }

    private fun publishIfChanged() {
        val s = fetch()
        if (s == last) return
        last = s
        frameId++
        CardFrames.publish(frameId, s)
        val art = Uri.parse("content://${CardArtProvider.AUTHORITY}/f/$frameId").toString()
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "card-$frameId")
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, s.subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, art)
            .putString(MediaMetadata.METADATA_KEY_ART_URI, art)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, art)
            .build()
        session.setMetadata(meta)
    }

    private fun music(command: String) {
        handler.post {
            try {
                contentResolver.call(ShareContract.URI, ShareContract.M_MUSIC, command, null)
            } catch (_: Exception) {
            }
        }
        publishState()
    }

    private fun publishState() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session.isActive = false
        session.release()
        CardFrames.clear()
        thread.quitSafely()
        super.onDestroy()
    }
}

/** Les dernières images publiées, retrouvées par numéro quand Android Auto vient les chercher. */
object CardFrames {
    private const val KEEP = 32
    private val frames = LinkedHashMap<Int, CardSnapshot>()

    @Synchronized
    fun publish(id: Int, s: CardSnapshot) {
        frames[id] = s
        while (frames.size > KEEP) frames.remove(frames.keys.first())
    }

    @Synchronized
    fun get(id: Int): CardSnapshot? = frames[id]

    @Synchronized
    fun clear() = frames.clear()
}
