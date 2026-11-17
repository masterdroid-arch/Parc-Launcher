package com.brightblu.parc.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.brightblu.parc.ParcLog
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderTtsChunk
import com.brightblu.parc.core.data.library.ReaderTtsRules
import java.util.Locale

/**
 * T257: reads a book with the system text-to-speech engine in a media-playback foreground service.
 * The notification is a normal media notification with play/pause, speed and stop, so the reading
 * continues with the screen off while an installed voice is available. No network is used by Parc;
 * the chosen system voice decides whether it needs downloaded data.
 */
class ReaderTtsService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pendingPlay = false
    private var waitingAtChapterEnd = false
    private var speed = ReaderTtsRules.DefaultSpeed
    private var stopAtChapterEnd = false
    private var plan: List<ReaderTtsChunk> = emptyList()
    private var index = 0
    private var utterance = ""
    private var foreground = false
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    /** T270: another app taking the audio pauses the reading; it never resumes on its own. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onMain { if (pendingPlay) pause() }
        }
    }

    /** T270: unplugging headphones or dropping a Bluetooth speaker pauses instead of speaking aloud. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onMain { if (pendingPlay) pause() }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val manager = getSystemService(AudioManager::class.java) ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        val granted = runCatching { manager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        runCatching {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        noisyRegistered = false
        runCatching { unregisterReceiver(noisyReceiver) }
    }

    /** Engine callbacks can arrive off the main thread; all playback fields stay on the main thread. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSession(this, "ParcReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { requestPlay() }
                override fun onPause() { pause() }
                override fun onStop() { finishAndStop() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStart -> startReading(intent)
            ActionPlay -> requestPlay()
            ActionPause -> pause()
            ActionSpeed -> changeSpeed(intent.getFloatExtra(ExtraSpeed, speed))
            ActionCycleSpeed -> changeSpeed(ReaderTtsRules.nextSpeed(speed))
            ActionStopAtEnd -> {
                stopAtChapterEnd = intent.getBooleanExtra(ExtraStopAtChapterEnd, stopAtChapterEnd)
                publish(ReaderTtsStateHolder.state.value.copy(stopAtChapterEnd = stopAtChapterEnd))
                updateNotification()
            }
            ActionStop -> finishAndStop()
            ActionRefresh -> if (foreground) {
                updateNotification()
            } else {
                val phase = ReaderTtsStateHolder.state.value.phase
                if (phase != ReaderTtsPhase.Preparing && phase != ReaderTtsPhase.Playing &&
                    phase != ReaderTtsPhase.Paused) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startReading(intent: Intent) {
        val session = ReaderTtsContent.session
        val wantedId = intent.getStringExtra(ExtraBookId).orEmpty()
        val doc = session?.doc
        if (session == null || doc == null || session.book.id != wantedId) {
            fail("This book is no longer open. Open it again to read it aloud.")
            return
        }
        speed = ReaderTtsRules.nearestSpeed(intent.getFloatExtra(ExtraSpeed, ReaderTtsRules.DefaultSpeed))
        stopAtChapterEnd = intent.getBooleanExtra(ExtraStopAtChapterEnd, false)
        val chapter = intent.getIntExtra(ExtraChapter, 0).coerceAtLeast(0)
        val offset = intent.getIntExtra(ExtraOffset, 0).coerceAtLeast(0)
        plan = ReaderTtsRules.plan(doc, chapter, offset)
        index = 0
        waitingAtChapterEnd = false
        pendingPlay = true
        failed = false
        showForeground()
        ensureEngine()
        if (ready) {
            speakCurrent()
        } else {
            publish(ReaderTtsState(session.book.id, ReaderTtsPhase.Preparing, chapter = chapter,
                chunkStart = offset, chunkEnd = offset, start = offset, end = offset,
                speed = speed, stopAtChapterEnd = stopAtChapterEnd))
            updateNotification()
        }
    }

    /** Starts the platform voice only after the foreground notification exists. */
    private fun ensureEngine() {
        if (tts != null && !failed) return
        failed = false
        ready = false
        runCatching { TextToSpeech(this, this) }
            .onSuccess { tts = it }
            .onFailure {
                ParcLog.e(Tag, "text-to-speech could not start", it)
                fail(NoVoice)
            }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            fail(NoVoice)
            return
        }
        val engine = tts
        if (engine == null) { fail(NoVoice); return }
        if (runCatching { engine.engines.isEmpty() }.getOrDefault(true)) { fail(NoVoice); return }
        val locale = runCatching { engine.defaultVoice?.locale }.getOrNull() ?: Locale.getDefault()
        val language = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val fallback = if (language < TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.setLanguage(Locale.getDefault()) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        } else language
        if (fallback < TextToSpeech.LANG_AVAILABLE) {
            fail("No voice for this language. Install a voice in the phone's text-to-speech settings.")
            return
        }
        runCatching {
            engine.setSpeechRate(speed)
            engine.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            engine.setOnUtteranceProgressListener(progress)
        }.onFailure {
            ParcLog.e(Tag, "voice setup failed", it)
            fail(NoVoice)
            return
        }
        ready = true
        if (pendingPlay) speakCurrent() else updateNotification()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = onMain {
            if (utteranceId == utterance && pendingPlay) {
                publish(ReaderTtsStateHolder.state.value.copy(phase = ReaderTtsPhase.Playing, message = ""))
                updateNotification()
            }
        }

        override fun onDone(utteranceId: String?) = onMain {
            if (utteranceId == null || utteranceId != utterance) return@onMain
            if (!pendingPlay) return@onMain
            utterance = ""
            index++
            speakCurrent()
        }

        @Deprecated("Deprecated in the platform, still called by older engines.")
        override fun onError(utteranceId: String?) = onMain { voiceError(utteranceId) }

        override fun onError(utteranceId: String?, errorCode: Int) = onMain { voiceError(utteranceId) }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = onMain {
            if (utteranceId == null || utteranceId != utterance) return@onMain
            val chunk = plan.getOrNull(index) ?: return@onMain
            val from = (chunk.start + start.coerceAtLeast(0)).coerceIn(chunk.start, chunk.end)
            val to = (chunk.start + end.coerceAtLeast(start)).coerceIn(from, chunk.end)
            publish(ReaderTtsStateHolder.state.value.copy(start = from, end = to))
        }
    }

    private fun voiceError(utteranceId: String?) {
        if (utteranceId != null && utteranceId != utterance) return
        pendingPlay = false
        failed = true
        publish(ReaderTtsStateHolder.state.value.copy(
            phase = ReaderTtsPhase.Paused,
            message = "This voice stopped. Try another voice in the phone's text-to-speech settings.",
        ))
        updateNotification()
    }

    private fun requestPlay() {
        val session = ReaderTtsContent.session ?: run {
            fail("This book is no longer open. Open it again to read it aloud.")
            return
        }
        if (failed || ReaderTtsStateHolder.state.value.phase == ReaderTtsPhase.Unavailable) {
            pendingPlay = true
            runCatching { tts?.shutdown() }
            tts = null
            if (plan.isEmpty()) {
                val at = ReaderTtsStateHolder.state.value
                plan = ReaderTtsRules.plan(session.doc, at.chapter, at.start)
                index = 0
            }
            showForeground()
            ensureEngine()
            publish(ReaderTtsStateHolder.state.value.copy(
                bookId = session.book.id, phase = ReaderTtsPhase.Preparing, message = "",
            ))
            updateNotification()
            return
        }
        if (plan.isEmpty()) {
            val position = ReaderTtsStateHolder.state.value
            plan = ReaderTtsRules.plan(session.doc, position.chapter, position.start)
            index = 0
            waitingAtChapterEnd = false
        }
        pendingPlay = true
        showForeground()
        ensureEngine()
        if (ready) speakCurrent() else {
            publish(ReaderTtsStateHolder.state.value.copy(phase = ReaderTtsPhase.Preparing, message = ""))
            updateNotification()
        }
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (!ready || !pendingPlay) return
        if (index >= plan.size) {
            publish(ReaderTtsStateHolder.state.value.copy(
                phase = ReaderTtsPhase.Finished, message = "Finished",
            ))
            finishAndStop(keepFinished = true)
            return
        }
        val chunk = plan[index]
        val previous = plan.getOrNull(index - 1)
        if (stopAtChapterEnd && !waitingAtChapterEnd && previous != null && previous.chapter != chunk.chapter) {
            pendingPlay = false
            waitingAtChapterEnd = true
            publish(ReaderTtsStateHolder.state.value.copy(
                phase = ReaderTtsPhase.Paused,
                chapter = previous.chapter,
                chunkStart = previous.start,
                chunkEnd = previous.end,
                start = previous.start,
                end = previous.end,
                message = "Stopped at the end of chapter ${previous.chapter + 1}",
            ))
            updateNotification()
            return
        }
        waitingAtChapterEnd = false
        if (!requestFocus()) {
            pendingPlay = false
            publish(ReaderTtsStateHolder.state.value.copy(phase = ReaderTtsPhase.Paused,
                message = "Another app is using the audio. Press play to try again."))
            updateNotification()
            return
        }
        registerNoisy()
        utterance = "parc-read-$index-${SystemClock.uptimeMillis()}"
        publish(ReaderTtsStateHolder.state.value.copy(
            phase = ReaderTtsPhase.Playing,
            chapter = chunk.chapter,
            chunkStart = chunk.start,
            chunkEnd = chunk.end,
            start = chunk.start,
            end = chunk.end,
            speed = speed,
            stopAtChapterEnd = stopAtChapterEnd,
            message = "",
        ))
        updateNotification()
        val result = runCatching {
            engine.setSpeechRate(speed)
            engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) fail("This voice could not read the book.")
    }

    private fun pause() {
        pendingPlay = false
        utterance = ""
        runCatching { tts?.stop() }
        abandonFocus()
        val current = ReaderTtsStateHolder.state.value
        if (current.phase == ReaderTtsPhase.Preparing || current.phase == ReaderTtsPhase.Playing) {
            publish(current.copy(phase = ReaderTtsPhase.Paused, message = ""))
        }
        updateNotification()
    }

    private fun changeSpeed(value: Float) {
        speed = ReaderTtsRules.nearestSpeed(value)
        runCatching { tts?.setSpeechRate(speed) }
        val wasPlaying = pendingPlay && ReaderTtsStateHolder.state.value.phase == ReaderTtsPhase.Playing
        publish(ReaderTtsStateHolder.state.value.copy(speed = speed))
        if (wasPlaying) {
            // Speech rate only affects the next utterance; restart this short chunk at the new speed.
            utterance = ""
            runCatching { tts?.stop() }
            pendingPlay = true
            speakCurrent()
        } else {
            updateNotification()
        }
    }

    private fun fail(message: String) {
        pendingPlay = false
        failed = true
        utterance = ""
        runCatching { tts?.stop() }
        abandonFocus()
        val session = ReaderTtsContent.session
        publish(ReaderTtsState(session?.book?.id.orEmpty(), ReaderTtsPhase.Unavailable,
            speed = speed, stopAtChapterEnd = stopAtChapterEnd, message = message))
        if (foreground) {
            updateNotification()
            stopForegroundCompat()
            foreground = false
        }
        stopSelf()
    }

    private fun finishAndStop(keepFinished: Boolean = false) {
        pendingPlay = false
        waitingAtChapterEnd = false
        utterance = ""
        runCatching { tts?.stop() }
        abandonFocus()
        unregisterNoisy()
        if (!keepFinished) {
            publish(ReaderTtsStateHolder.state.value.copy(phase = ReaderTtsPhase.Idle, message = ""))
        }
        stopForegroundCompat()
        foreground = false
        runCatching { mediaSession?.isActive = false }
        stopSelf()
    }

    private fun showForeground() {
        if (!foreground) {
            startForeground(NotificationId, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        }
        updateNotification()
    }

    private fun updateNotification() {
        if (!foreground) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(Tag, NotificationId, buildNotification())
        }.onFailure { ParcLog.e(Tag, "read-aloud notification failed", it) }
        syncMediaSession()
    }

    private fun syncMediaSession() {
        val session = mediaSession ?: return
        val state = ReaderTtsStateHolder.state.value
        val playback = when (state.phase) {
            ReaderTtsPhase.Playing -> PlaybackState.STATE_PLAYING
            ReaderTtsPhase.Paused, ReaderTtsPhase.Finished -> PlaybackState.STATE_PAUSED
            ReaderTtsPhase.Preparing -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_NONE
        }
        runCatching {
            session.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(playback, state.start.toLong(), state.speed)
                .build())
        }
    }

    private fun buildNotification(): Notification {
        val state = ReaderTtsStateHolder.state.value
        val session = ReaderTtsContent.session
        val title = session?.book?.title ?: "Read aloud"
        val status = when (state.phase) {
            ReaderTtsPhase.Playing -> "Reading · chapter ${state.chapter + 1}"
            ReaderTtsPhase.Paused -> "Paused · chapter ${state.chapter + 1}"
            ReaderTtsPhase.Preparing -> "Preparing voice"
            ReaderTtsPhase.Finished -> "Finished"
            ReaderTtsPhase.Unavailable -> state.message
            ReaderTtsPhase.Idle -> "Read aloud"
        }
        val text = state.message.ifBlank { status }
        val playing = state.phase == ReaderTtsPhase.Playing
        val toggle = servicePending(if (playing) ActionPause else ActionPlay, 10)
        val speedAction = servicePending(ActionCycleSpeed, 11)
        val stop = servicePending(ActionStop, 12)
        val builder = Notification.Builder(this, ChannelId)
            .setSmallIcon(com.brightblu.parc.R.drawable.ic_read_aloud)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openReaderPending())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.phase == ReaderTtsPhase.Playing || state.phase == ReaderTtsPhase.Paused ||
                state.phase == ReaderTtsPhase.Preparing)
            .addAction(Notification.Action.Builder(null, if (playing) "Pause" else "Play", toggle).build())
            .addAction(Notification.Action.Builder(null, "Speed ${ReaderTtsRules.speedLabel(state.speed)}", speedAction).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        val token = mediaSession?.sessionToken
        if (token != null) {
            builder.style = Notification.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2)
        }
        return builder.build()
    }

    private fun openReaderPending(): PendingIntent {
        val state = ReaderTtsStateHolder.state.value
        val session = ReaderTtsContent.session
        val intent = if (session != null) {
            ReaderContract.intent(this, session.book, BookPosition(state.chapter, state.start),
                unadded = session.unadded, openHash = session.openHash).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Intent(this, ReaderActivity::class.java)
        }
        return PendingIntent.getActivity(this, 20, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun servicePending(action: String, request: Int): PendingIntent =
        PendingIntent.getService(this, request,
            Intent(this, ReaderTtsService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun publish(value: ReaderTtsState) {
        ReaderTtsStateHolder.set(value)
        syncMediaSession()
    }

    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    override fun onDestroy() {
        abandonFocus()
        unregisterNoisy()
        pendingPlay = false
        utterance = ""
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        runCatching { mediaSession?.release() }
        mediaSession = null
        val phase = ReaderTtsStateHolder.state.value.phase
        if (phase == ReaderTtsPhase.Playing || phase == ReaderTtsPhase.Paused || phase == ReaderTtsPhase.Preparing) {
            publish(ReaderTtsStateHolder.state.value.copy(phase = ReaderTtsPhase.Idle, message = ""))
        }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(Tag, NotificationId)
        }
        ReaderTtsContent.clear()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) return
        val channel = NotificationChannel(ChannelId, "Read aloud", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Reading a book aloud with the phone's voice"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val Tag = "read-aloud"
        private const val NotificationId = 7201
        private const val ChannelId = "read_aloud"
        private const val NoVoice = "No text-to-speech voice on this phone. Install one in the phone's text-to-speech settings."

        const val ActionStart = "com.brightblu.parc.reader.TTS_START"
        const val ActionPlay = "com.brightblu.parc.reader.TTS_PLAY"
        const val ActionPause = "com.brightblu.parc.reader.TTS_PAUSE"
        const val ActionSpeed = "com.brightblu.parc.reader.TTS_SPEED"
        const val ActionCycleSpeed = "com.brightblu.parc.reader.TTS_CYCLE_SPEED"
        const val ActionStopAtEnd = "com.brightblu.parc.reader.TTS_STOP_AT_END"
        const val ActionStop = "com.brightblu.parc.reader.TTS_STOP"
        const val ActionRefresh = "com.brightblu.parc.reader.TTS_REFRESH"
        private const val ExtraBookId = "book_id"
        private const val ExtraChapter = "chapter"
        private const val ExtraOffset = "offset"
        private const val ExtraSpeed = "speed"
        private const val ExtraStopAtChapterEnd = "stop_at_end"

        internal fun start(context: Context, session: ReaderTtsSession, position: BookPosition,
            speed: Float, stopAtChapterEnd: Boolean) {
            ReaderTtsContent.put(session)
            ReaderTtsStateHolder.set(ReaderTtsState(
                bookId = session.book.id,
                phase = ReaderTtsPhase.Preparing,
                chapter = position.chapter,
                chunkStart = position.characterOffset,
                chunkEnd = position.characterOffset,
                start = position.characterOffset,
                end = position.characterOffset,
                speed = ReaderTtsRules.nearestSpeed(speed),
                stopAtChapterEnd = stopAtChapterEnd,
            ))
            val intent = Intent(context, ReaderTtsService::class.java)
                .setAction(ActionStart)
                .putExtra(ExtraBookId, session.book.id)
                .putExtra(ExtraChapter, position.chapter)
                .putExtra(ExtraOffset, position.characterOffset)
                .putExtra(ExtraSpeed, speed)
                .putExtra(ExtraStopAtChapterEnd, stopAtChapterEnd)
            runCatching { context.startForegroundService(intent) }.onFailure { error ->
                ParcLog.e(Tag, "read aloud could not start", error)
                ReaderTtsStateHolder.update {
                    it.copy(phase = ReaderTtsPhase.Unavailable,
                        message = "Read aloud could not start on this phone.")
                }
            }
        }

        internal fun play(context: Context) = send(context, ActionPlay)
        internal fun pause(context: Context) = send(context, ActionPause)
        internal fun setSpeed(context: Context, speed: Float) =
            send(context, ActionSpeed, Intent().putExtra(ExtraSpeed, speed))
        internal fun setStopAtChapterEnd(context: Context, stop: Boolean) =
            send(context, ActionStopAtEnd, Intent().putExtra(ExtraStopAtChapterEnd, stop))
        internal fun refresh(context: Context) {
            if (ReaderTtsStateHolder.state.value.phase == ReaderTtsPhase.Idle) return
            send(context, ActionRefresh)
        }

        internal fun stop(context: Context) {
            ReaderTtsStateHolder.update {
                if (it.phase == ReaderTtsPhase.Finished || it.phase == ReaderTtsPhase.Unavailable) it
                else it.copy(phase = ReaderTtsPhase.Idle, message = "")
            }
            ReaderTtsContent.clear()
            runCatching { context.stopService(Intent(context, ReaderTtsService::class.java)) }
        }

        private fun send(context: Context, action: String, extras: Intent? = null) {
            val intent = Intent(context, ReaderTtsService::class.java).setAction(action)
            extras?.let { intent.putExtras(it) }
            runCatching { context.startService(intent) }
        }
    }
}
