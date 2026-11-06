package com.ozguryilmaz.navinavi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub'daki en son release'i kontrol eder, APK'yı indirir ve yükleyiciyi açar.
 * Release etiketleri "b<derleme no>" biçimindedir; derleme no = versionCode.
 */
class Updater(private val context: Context) {

    data class Release(val buildNumber: Int, val apkUrl: String)

    suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        when (conn.responseCode) {
            200 -> {}
            404 -> return@withContext null
            else -> throw IllegalStateException("GitHub yanıtı: HTTP ${conn.responseCode}")
        }
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val number = json.getString("tag_name").removePrefix("b").toIntOrNull() ?: return@withContext null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return@withContext Release(number, a.getString("browser_download_url"))
            }
        }
        null
    }

    suspend fun download(release: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "navinavi-${release.buildNumber}.apk")

        val conn = open(release.apkUrl)
        if (conn.responseCode != 200) throw IllegalStateException("İndirme hatası: HTTP ${conn.responseCode}")

        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPercent = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != lastPercent) {
                            lastPercent = p
                            withContext(Dispatchers.Main) { onProgress(p) }
                        }
                    }
                }
            }
        }
        file
    }

    /** Telefon "bilinmeyen uygulamaları yükle" iznini vermediyse ayar ekranını açar ve false döner. */
    fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return false
        }
        return true
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
}
