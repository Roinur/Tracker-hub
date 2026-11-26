package com.roinur.trackerhub.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val latestTag: String,
    val displayVersion: String,
    val releaseUrl: String
)

object AppUpdateChecker {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    suspend fun fetchLatestRelease(githubRepoUrl: String): AppReleaseInfo? = withContext(Dispatchers.IO) {
        val latestUrl = "${githubRepoUrl.trimEnd('/')}/releases/latest"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(latestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "TrackerHub-Android")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: return@withContext null
                val tag = extractTagFromReleaseUrl(location) ?: return@withContext null
                val normalized = normalizeVersionDisplay(tag) ?: return@withContext null
                val resolvedUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    "https://github.com$location"
                }
                AppReleaseInfo(
                    latestTag = tag,
                    displayVersion = normalized,
                    releaseUrl = resolvedUrl
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun extractTagFromReleaseUrl(location: String): String? {
        val marker = "/releases/tag/"
        val idx = location.indexOf(marker)
        if (idx < 0) return null
        val tail = location.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        return tail.takeIf { it.isNotEmpty() }
    }

    internal fun normalizeVersionDisplay(raw: String): String? {
        val parts = parseVersionNumbers(raw) ?: return null
        return parts.joinToString(".")
    }

    fun isNewerVersion(installedVersion: String?, latestVersion: String?): Boolean {
        if (installedVersion.isNullOrBlank() || latestVersion.isNullOrBlank()) return false
        val installed = parseVersionNumbers(installedVersion) ?: return false
        val latest = parseVersionNumbers(latestVersion) ?: return false
        val maxLen = maxOf(installed.size, latest.size)
        for (i in 0 until maxLen) {
            val a = installed.getOrElse(i) { 0 }
            val b = latest.getOrElse(i) { 0 }
            if (b != a) return b > a
        }
        return false
    }

    internal fun parseVersionNumbers(raw: String): List<Int>? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val match = Regex("^(\\d+(?:\\.\\d+)*)").find(trimmed) ?: return null
        val parts = match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
        return parts.takeIf { it.isNotEmpty() }
    }
}
