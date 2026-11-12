package com.lsantosnet.newsflow.watch.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.lsantosnet.newsflow.watch.R
import com.lsantosnet.newsflow.watch.data.Article
import com.lsantosnet.newsflow.watch.data.Labels
import com.lsantosnet.newsflow.watch.repository
import com.lsantosnet.newsflow.watch.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Fila de leitura em voz alta, observada pela tela "Ouvir". */
data class PlayerState(
    val queue: List<Article> = emptyList(),
    val index: Int = 0,
    val groupName: String = "",
    val playing: Boolean = false,
    /** 0 a 1 dentro do artigo atual. */
    val progress: Float = 0f,
) {
    val current: Article? get() = queue.getOrNull(index)
    val active: Boolean get() = queue.isNotEmpty()
}

object Player {
    val state = MutableStateFlow(PlayerState())
}

/**
 * Lê os artigos em voz alta com o motor de TTS do próprio relógio, um após o
 * outro, e marca cada um como lido ao terminar.
 *
 * Roda como foreground service de mídia para continuar com a tela apagada
 * (mesmo motivo do `PodcastPlaybackService` do app do celular) e publica uma
 * sessão de mídia, para os botões do fone Bluetooth pausarem e pularem.
 */
class ReaderService : Service(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var ttsReady = false
    private var speakWhenReady = false

    /** Texto completo do artigo atual e onde, dentro dele, a fala atual começou. */
    private var fullText = ""
    private var baseOffset = 0
    private var lastOffset = 0

    /** Muda a cada fala; callbacks de falas antigas (interrompidas) são ignorados. */
    private var generation = 0

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(speechAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    main.post { pause() }
                }
            }
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Leitura em voz alta", NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "NewsFlow").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onStop() = finish()
            })
        }
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return fail("O relógio não tem um motor de voz disponível")
        val language = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            return fail("A voz em português não está instalada no relógio")
        }
        tts.setAudioAttributes(speechAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != currentId()) return
                lastOffset = baseOffset + start
                val length = fullText.length.coerceAtLeast(1)
                Player.state.update { it.copy(progress = (lastOffset.toFloat() / length).coerceIn(0f, 1f)) }
            }

            override fun onDone(utteranceId: String?) {
                main.post { if (utteranceId == currentId()) articleFinished() }
            }

            @Deprecated("Exigido pela classe base")
            override fun onError(utteranceId: String?) {
                main.post { if (utteranceId == currentId()) advance(1) }
            }
        })
        ttsReady = true
        if (speakWhenReady) {
            speakWhenReady = false
            speakCurrent(baseOffset)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Todo comando recoloca o serviço em primeiro plano: quem chama usa
        // startForegroundService, que exige isso em poucos segundos.
        showNotification()
        if (!Player.state.value.active) {
            finish()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> speakCurrent(0)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> skip(1)
            ACTION_PREVIOUS -> skip(-1)
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun currentId() = "u$generation"

    private fun speakCurrent(fromOffset: Int) {
        val state = Player.state.value
        val article = state.current ?: return finish()
        fullText = Labels.narration(state.index + 1, state.queue.size, article)
        baseOffset = fromOffset.coerceIn(0, fullText.length)
        lastOffset = baseOffset
        if (!ttsReady) {
            speakWhenReady = true
            Player.state.update { it.copy(playing = true) }
            return
        }
        audio.requestAudioFocus(focusRequest)
        acquireWakeLock()
        generation++
        tts.speak(fullText.substring(baseOffset), TextToSpeech.QUEUE_FLUSH, Bundle(), currentId())
        Player.state.update {
            it.copy(playing = true, progress = baseOffset.toFloat() / fullText.length.coerceAtLeast(1))
        }
        publish()
    }

    /** O artigo foi ouvido até o fim: marca como lido no Firestore e segue a fila. */
    private fun articleFinished() {
        Player.state.value.current?.let {
            holdAwakeForWrite()
            repository.markRead(it)
        }
        advance(1)
    }

    /**
     * Mantém o relógio acordado pelo tempo das tentativas de gravação. Sem
     * isto, no último artigo da fila o serviço encerra, solta o wake lock da
     * leitura e o aparelho pode dormir antes de o "lido" chegar ao Firestore.
     */
    private fun holdAwakeForWrite() {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "newsflow:mark-read")
            .acquire(WRITE_WINDOW_MILLIS)
    }

    /** Vai para outro artigo da fila; passar do último encerra a leitura. */
    private fun advance(delta: Int) {
        val state = Player.state.value
        val next = state.index + delta
        if (next >= state.queue.size) return finish()
        Player.state.update { it.copy(index = next.coerceAtLeast(0), progress = 0f) }
        speakCurrent(0)
    }

    private fun skip(delta: Int) {
        if (!Player.state.value.active) return
        silence()
        advance(delta)
    }

    /**
     * O TTS do Android não tem pausa de verdade: interrompe a fala e guarda a
     * última palavra anunciada, de onde [resume] continua.
     */
    private fun pause() {
        if (!Player.state.value.playing) return
        silence()
        speakWhenReady = false
        Player.state.update { it.copy(playing = false) }
        audio.abandonAudioFocusRequest(focusRequest)
        releaseWakeLock()
        publish()
    }

    private fun resume() {
        val state = Player.state.value
        if (!state.active || state.playing) return
        speakCurrent(if (fullText.isEmpty()) 0 else lastOffset)
    }

    private fun silence() {
        generation++
        if (ttsReady) tts.stop()
    }

    private fun fail(message: String) {
        repository.messages.tryEmit(message)
        finish()
    }

    private fun finish() {
        silence()
        speakWhenReady = false
        Player.state.value = PlayerState()
        audio.abandonAudioFocusRequest(focusRequest)
        releaseWakeLock()
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        generation++
        tts.shutdown()
        session.release()
        releaseWakeLock()
        if (Player.state.value.active) Player.state.value = PlayerState()
        super.onDestroy()
    }

    // ---------- notificação, mostrador e sessão de mídia ----------

    private fun publish() {
        val state = Player.state.value
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.current?.title.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.groupName)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        session.isActive = state.active
        if (state.active) showNotification()
    }

    private fun showNotification() {
        val state = Player.state.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = state.current?.title ?: getString(R.string.app_name)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(title)
            .setContentText(if (state.active) "${state.index + 1} de ${state.queue.size} · ${state.groupName}" else "")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                0,
                if (state.playing) "Pausar" else "Ouvir",
                command(if (state.playing) ACTION_PAUSE else ACTION_RESUME, 1),
            )
            .addAction(0, "Próximo", command(ACTION_NEXT, 2))

        // Ícone no mostrador do relógio enquanto a leitura está em andamento.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_stat_reader)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(title).build())
            .build()
            .apply(applicationContext)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun command(action: String, requestCode: Int): PendingIntent = PendingIntent.getForegroundService(
        this,
        requestCode,
        Intent(this, ReaderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "newsflow:reader")
            .apply { acquire(MAX_SESSION_MILLIS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "reader"
        private const val NOTIFICATION_ID = 1
        private const val MAX_SESSION_MILLIS = 2 * 60 * 60 * 1000L
        private const val WRITE_WINDOW_MILLIS = 40_000L

        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREVIOUS = "previous"
        private const val ACTION_STOP = "stop"

        /** Começa a ler [queue] a partir de [startIndex]. */
        fun play(context: Context, queue: List<Article>, startIndex: Int, groupName: String) {
            if (queue.isEmpty()) return
            Player.state.value = PlayerState(
                queue = queue,
                index = startIndex.coerceIn(0, queue.lastIndex),
                groupName = groupName,
                playing = true,
            )
            send(context, ACTION_PLAY)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun next(context: Context) = send(context, ACTION_NEXT)
        fun previous(context: Context) = send(context, ACTION_PREVIOUS)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            if (!Player.state.value.active) return
            context.startForegroundService(Intent(context, ReaderService::class.java).setAction(action))
        }
    }
}
