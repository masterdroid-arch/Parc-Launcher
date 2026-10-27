package com.brightblu.parc.core.data.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService

/**
 * The platform port for the Media page (T99). Listing sessions needs notification access for the
 * existing listener component; without it the platform throws SecurityException and the result is
 * [MediaRead.NoAccess]. Nothing here throws to the caller, and the cover is downscaled and kept in
 * memory for the current track only (never written to disk).
 */
class AndroidMediaSource(context: Context) : MediaSource, MediaControls {
    private val appContext = context.applicationContext
    private val listener = ComponentName(appContext, ParcNotificationListenerService::class.java)
    private var controllers: Map<String, MediaController> = emptyMap()
    private var coverKey: String? = null
    private var cover: Bitmap? = null

    override fun read(): MediaRead = try {
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
        if (manager == null) MediaRead.Sessions(emptyList()) else {
            val active = manager.getActiveSessions(listener)
            val map = LinkedHashMap<String, MediaController>()
            val infos = ArrayList<MediaSessionInfo>(active.size)
            for (controller in active) {
                val info = infoOf(controller) ?: continue
                map[info.id] = controller
                infos += info
            }
            controllers = map
            MediaRead.Sessions(MediaRules.visible(infos))
        }
    } catch (e: SecurityException) {
        controllers = emptyMap()
        clearCover()
        MediaRead.NoAccess
    } catch (e: Exception) {
        MediaRead.Sessions(emptyList())
    }

    /** The cover of the session, decoded small; null when the track has none. */
    fun coverFor(id: String): Bitmap? = try {
        val meta = controllers[id]?.metadata
        if (meta == null) null else {
            val key = id + "|" + meta.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
            if (key == coverKey) cover else {
                val raw = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                cover = raw?.let(::downscale)
                coverKey = key
                cover
            }
        }
    } catch (e: Exception) {
        null
    }

    fun clearCover() { cover = null; coverKey = null }

    private fun downscale(source: Bitmap): Bitmap? = try {
        val longest = maxOf(source.width, source.height)
        if (longest <= 0) null
        else if (longest <= CoverPx) source.copy(Bitmap.Config.ARGB_8888, false)
        else {
            val scale = CoverPx.toFloat() / longest
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        }
    } catch (e: Exception) {
        null
    }

    private fun infoOf(controller: MediaController): MediaSessionInfo? = try {
        val meta = controller.metadata
        val playback = controller.playbackState
        val actions = playback?.actions ?: 0L
        MediaSessionInfo(
            id = controller.packageName + "#" + controller.sessionToken.hashCode(),
            packageName = controller.packageName,
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            state = stateOf(playback?.state),
            positionMs = playback?.position ?: 0L,
            updatedElapsedMs = playback?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = playback?.playbackSpeed ?: 1f,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    } catch (e: Exception) {
        null
    }

    private fun stateOf(state: Int?): MediaPlayState = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> MediaPlayState.Playing
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> MediaPlayState.Buffering
        PlaybackState.STATE_PAUSED -> MediaPlayState.Paused
        else -> MediaPlayState.Stopped
    }

    private inline fun send(id: String, block: (MediaController) -> Unit): Boolean = try {
        val controller = controllers[id]
        if (controller == null) false else { block(controller); true }
    } catch (e: Exception) {
        false
    }

    override fun playPause(id: String): Boolean = send(id) { c ->
        val state = c.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) c.transportControls.pause()
        else c.transportControls.play()
    }

    override fun previous(id: String): Boolean = send(id) { it.transportControls.skipToPrevious() }
    override fun next(id: String): Boolean = send(id) { it.transportControls.skipToNext() }

    override fun openApp(id: String): Boolean = try {
        val controller = controllers[id]
        val pending = controller?.sessionActivity
        if (pending != null) {
            com.brightblu.parc.core.data.PendingIntentLaunch.send(pending)
            true
        } else if (controller != null) {
            val intent = appContext.packageManager.getLaunchIntentForPackage(controller.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
                true
            } else false
        } else false
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val CoverPx = 160
    }
}
