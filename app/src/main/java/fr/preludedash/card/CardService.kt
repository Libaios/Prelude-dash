package fr.preludedash.card

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.media.MediaBrowserService
import fr.preludedash.data.CalStore
import fr.preludedash.link.DataHub
import fr.preludedash.link.LinkManager
import fr.preludedash.music.MusicRelay

/**
 * La carte "musique" d'Android Auto, celle qui se place à côté de Waze.
 *
 * Ce n'est pas un lecteur : la session sert de support à la carte. Elle ne joue aucun son
 * et ne prend jamais la main sur l'audio. Les boutons de la carte et du volant sont
 * relayés vers l'appli de musique en cours (Spotify) via [MusicRelay].
 *
 * La session reste toujours en lecture : Android Auto garde ainsi la carte de Prelude Dash
 * affichée, et le bouton visible est toujours "pause", que l'on traite comme lecture/pause.
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

    private val musicChanged = Runnable { handler.post { publishIfChanged(force = true) } }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "prelude-dash-card")
        session.setCallback(object : MediaSession.Callback() {
            // Envoyé quand Android Auto sélectionne la carte : on ne relance rien
            override fun onPlay() = publishState()

            // Le bouton affiché (pause) et celui du volant : lecture/pause de Spotify
            override fun onPause() {
                MusicRelay.toggle()
                publishState()
            }

            override fun onStop() = publishState()

            override fun onSkipToNext() {
                MusicRelay.next()
                publishState()
            }

            override fun onSkipToPrevious() {
                MusicRelay.previous()
                publishState()
            }

            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                if (mediaId == ITEM_RESUME) MusicRelay.play()
                publishState()
            }
        })
        session.isActive = true
        sessionToken = session.sessionToken

        LinkManager.acquire(this, "card")
        MusicRelay.start(this)
        MusicRelay.addListener(musicChanged)
        publishState()
        handler.post(tick)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        // Un seul élément, pour que l'écran de l'appli musique ne soit pas vide
        val desc = MediaDescription.Builder()
            .setMediaId(ITEM_RESUME)
            .setTitle("Reprendre la musique")
            .setSubtitle(MusicRelay.line() ?: "Relance l'appli de musique en cours")
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    private fun publishIfChanged(force: Boolean = false) {
        val s = CardSnapshot.from(DataHub.data, DataHub.state, DataHub.ageMs(), CalStore.get(this))
        if (!force && s == last) return
        last = s
        frameId++
        CardFrames.publish(frameId, s)
        val art = CardArtProvider.uriFor(frameId).toString()
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
        MusicRelay.removeListener(musicChanged)
        LinkManager.release("card")
        session.isActive = false
        session.release()
        CardFrames.clear()
        thread.quitSafely()
        super.onDestroy()
    }
}
