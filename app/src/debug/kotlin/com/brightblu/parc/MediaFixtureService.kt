package com.brightblu.parc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/** Debug-only: publishes one fake media session ("Fixture Song") while this service lives (start with am startservice). */
class MediaFixtureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        start()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    private var session: MediaSession? = null
    private fun start() {
        val art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(art).drawColor(Color.rgb(200, 90, 60))
        val s = MediaSession(this, "fixture")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPause() = state(PlaybackState.STATE_PAUSED)
            override fun onPlay() = state(PlaybackState.STATE_PLAYING)
        })
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture Song")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test Artist")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Test Album")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 215_000)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, art).build())
        s.isActive = true
        session = s
        state(PlaybackState.STATE_PLAYING)
    }

    private fun state(value: Int) {
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
            .setState(value, 42_000, 1f, SystemClock.elapsedRealtime()).build())
    }

    override fun onDestroy() {
        session?.release()
        super.onDestroy()
    }
}
