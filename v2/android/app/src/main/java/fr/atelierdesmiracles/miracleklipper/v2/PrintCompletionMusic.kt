package fr.atelierdesmiracles.miracleklipper.v2

import android.content.Context
import android.media.AudioAttributes
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
    private var player: MediaPlayer? = null

    val enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    val selectedTrack: Uri?
        get() = preferences.getString(KEY_TRACK_URI, null)?.let(Uri::parse)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setSelectedTrack(uri: Uri) {
        preferences.edit().putString(KEY_TRACK_URI, uri.toString()).apply()
    }

    fun playSelectedTrack(): Boolean {
        val track = selectedTrack ?: return false
        stop()
        return try {
            val next = MediaPlayer()
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            next.setDataSource(appContext, track)
            next.setOnCompletionListener { releasePlayer(it) }
            next.setOnErrorListener { mp, _, _ -> releasePlayer(mp); true }
            next.prepare()
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
        val current = player ?: return
        player = null
        try {
            if (current.isPlaying) current.stop()
        } catch (_: IllegalStateException) {
            // The player may already have entered an error/completed state.
        } finally {
            current.release()
        }
    }

    private fun releasePlayer(completed: MediaPlayer) {
        if (player === completed) player = null
        completed.release()
    }

    companion object {
        private const val PREFS = "print_completion_music"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TRACK_URI = "track_uri"
    }
}
