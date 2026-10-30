package fr.atelierdesmiracles.miracleklipper.v2

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri

/**
 * Android-native completion music. This deliberately runs in the host app,
 * not inside the Alpine/QEMU guest. Call playForCompletedPrint() only after
 * Moonraker confirms a successful print completion.
 */
class PrintCompletionMusic(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val current = player ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                current.setVolume(1f, 1f)
                if (!current.isPlaying) {
                    try { current.start() } catch (_: IllegalStateException) { }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                try { if (current.isPlaying) current.pause() } catch (_: IllegalStateException) { }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> current.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_LOSS -> stop()
        }
    }

    val enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    val selectedTrack: Uri?
        get() = preferences.getString(KEY_TRACK_URI, null)?.let(Uri::parse)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) stop()
    }

    fun setSelectedTrack(uri: Uri) {
        preferences.edit().putString(KEY_TRACK_URI, uri.toString()).apply()
    }

    fun playSelectedTrack(): Boolean {
        val track = selectedTrack ?: return false
        stop()
        return try {
            val next = MediaPlayer()
            next.setAudioAttributes(audioAttributes)
            next.setDataSource(appContext, track)
            next.setOnCompletionListener { releasePlayer(it) }
            next.setOnErrorListener { mp, _, _ -> releasePlayer(mp); true }
            next.prepare()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = request
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                releaseAudioFocus()
                next.release()
                return false
            }

            player = next
            next.start()
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun playForCompletedPrint(): Boolean = enabled && playSelectedTrack()

    fun stop() {
        val current = player
        player = null
        if (current != null) {
            try {
                if (current.isPlaying) current.stop()
            } catch (_: IllegalStateException) {
                // The player may already have entered an error/completed state.
            } finally {
                current.release()
            }
        }
        releaseAudioFocus()
    }

    private fun releasePlayer(completed: MediaPlayer) {
        if (player === completed) player = null
        completed.release()
        releaseAudioFocus()
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val PREFS = "print_completion_music"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TRACK_URI = "track_uri"
    }
}
