package fr.atelierdesmiracles.miracleklipper.v2

import android.content.Context
import android.net.Uri
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class RuntimeDownloadResult {
    data class Installed(val sha256: String, val bytes: Long) : RuntimeDownloadResult()
    object AlreadyCurrent : RuntimeDownloadResult()
    data class Failed(val reason: String) : RuntimeDownloadResult()
}

object RuntimeBundleDownloader {
    private const val RELEASE_API = "https://api.github.com/repos/atelierdesmiracles/Miracle-Klipper-V2/releases/tags/miracle-klipper-runtime-latest"
    private const val ARCHIVE_NAME = "miracle-klipper-runtime-alpine-3.24.3-arm64.zip"
    private const val CHECKSUM_NAME = "$" + "ARCHIVE_NAME.sha256"
    private const val PREFS = "miracle_runtime_download"
    private const val PREF_SHA = "installed_archive_sha256"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun downloadAndInstall(context: Context, force: Boolean = false, onProgress: (String) -> Unit = {}): RuntimeDownloadResult {
        val app = context.applicationContext
        val archive = File(app.cacheDir, "$" + "ARCHIVE_NAME.part")
        val finalArchive = File(app.cacheDir, ARCHIVE_NAME)
        try {
            onProgress("Recherche du runtime Miracle Klipper sur GitHub…")
            val releaseRequest = Request.Builder().url(RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Miracle-Klipper-Android").get().build()
            val release = JSONObject(executeText(releaseRequest))
            require(!release.optBoolean("draft", true)) { "La publication du runtime est encore en brouillon." }
            val assets = release.getJSONArray("assets")
            var archiveUrl: String? = null
            var checksumUrl: String? = null
            var archiveSize = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.optString("name")) {
                    ARCHIVE_NAME -> { archiveUrl = asset.optString("browser_download_url"); archiveSize = asset.optLong("size", 0L) }
                    CHECKSUM_NAME -> checksumUrl = asset.optString("browser_download_url")
                }
            }
            require(!archiveUrl.isNullOrBlank()) { "Archive runtime absente de la publication GitHub." }
            require(!checksumUrl.isNullOrBlank()) { "Fichier SHA-256 absent de la publication GitHub." }
            val checksumText = executeText(Request.Builder().url(checksumUrl!!).header("User-Agent", "Miracle-Klipper-Android").get().build()).trim()
            val expected = checksumText.substringBefore(" ").substringBefore("\t").lowercase()
            require(expected.matches(Regex("[a-f0-9]{64}"))) { "Le fichier SHA-256 publié est invalide." }

            val installedSha = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SHA, null)
            if (!force && installedSha == expected && RuntimeBundleInstaller.verifyInstalled(app)) return RuntimeDownloadResult.AlreadyCurrent
            require(!VmRuntimeService.running) { "Arrêtez la machine virtuelle avant de mettre à jour son runtime." }
            val available = StatFs(app.filesDir.absolutePath).availableBytes
            val required = maxOf(archiveSize * 2L + 256L * 1024L * 1024L, 768L * 1024L * 1024L)
            require(available >= required) {
                "Espace insuffisant : " + (available / 1048576L) + " Mo libres, environ " + (required / 1048576L) + " Mo nécessaires."
            }

            archive.delete()
            finalArchive.delete()
            onProgress("Téléchargement du runtime (" + (archiveSize / 1048576L) + " Mo)…")
            val request = Request.Builder().url(archiveUrl!!).header("User-Agent", "Miracle-Klipper-Android").get().build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Téléchargement GitHub refusé (HTTP " + response.code + ")." }
                val body = response.body ?: error("Réponse GitHub vide.")
                FileOutputStream(archive).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var received = 0L
                        var lastReport = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            if (received - lastReport >= 4L * 1024L * 1024L) {
                                val total = if (body.contentLength() > 0) body.contentLength() else archiveSize
                                val percent = if (total > 0) (received * 100L / total).coerceAtMost(100L) else 0L
                                onProgress("Téléchargement du runtime : " + percent + " % (" + (received / 1048576L) + " Mo)")
                                lastReport = received
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
            onProgress("Vérification SHA-256 de l’archive…")
            val actual = sha256(archive)
            require(actual == expected) { "SHA-256 incorrect : l’archive téléchargée est corrompue." }
            require(archive.length() > 0L) { "L’archive téléchargée est vide." }
            onProgress("Installation et contrôle des fichiers du runtime…")
            when (val install = RuntimeBundleInstaller.install(app, Uri.fromFile(archive))) {
                is RuntimeInstallResult.Installed -> {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_SHA, expected).apply()
                    finalArchive.delete()
                    if (!archive.renameTo(finalArchive)) archive.delete()
                    return RuntimeDownloadResult.Installed(expected, archiveSize)
                }
                is RuntimeInstallResult.Failed -> error(install.reason)
            }
        } catch (error: Exception) {
            archive.delete()
            return RuntimeDownloadResult.Failed(error.message ?: "Échec du téléchargement du runtime.")
        }
    }

    private fun executeText(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub a répondu HTTP " + response.code + ".")
            return response.body?.string() ?: error("Réponse GitHub vide.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
