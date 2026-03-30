package com.arata.yukarilauncher.feature.download.platform.curseforge.update

import com.arata.yukarilauncher.feature.download.platform.update.ModUpdate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object CurseForgeUpdateHelper {
    private val client = OkHttpClient()
    private const val BASE_URL = "https://api.curseforge.com/v1"

    private val API_KEY: String by lazy {
        val file = File("curseforge_key.txt")
        if (file.exists()) file.readText().trim() else ""
    }

    private fun versionsEquivalent(current: String, latest: String): Boolean {
        val normalizedCurrent = current.trim().lowercase()
            .removePrefix("v")
            .replace(" ", "")
        val normalizedLatest = latest.trim().lowercase()
            .removePrefix("v")
            .replace(" ", "")
        return normalizedCurrent.isNotBlank() &&
            normalizedCurrent != "unknown" &&
            !normalizedCurrent.contains("\${") &&
            normalizedCurrent == normalizedLatest
    }

    fun checkUpdate(modId: String, currentVersion: String, minecraftVersion: String, currentFileName: String? = null): ModUpdate? {
        val url = "$BASE_URL/mods/$modId/files?gameVersion=$minecraftVersion"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", API_KEY)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val data = JSONObject(response.body?.string() ?: return null).getJSONArray("data")
            if (data.length() == 0) return null

            val latest = data.getJSONObject(0)
            val latestVersion = latest.getString("displayName")
            val downloadUrl = latest.getString("downloadUrl")
            val fileName = latest.getString("fileName")  // e.g., "Sodium-0.8.7+mc1.21.11.jar"
            val installedIndex = currentFileName?.let { installed ->
                (0 until data.length()).firstOrNull { i ->
                    installed.equals(data.getJSONObject(i).optString("fileName"), ignoreCase = true)
                }
            }
            val needsUpdate = when {
                installedIndex != null -> installedIndex != 0
                else -> !versionsEquivalent(currentVersion, latestVersion)
            }
            val resolvedCurrentVersion = if (installedIndex != null) {
                data.getJSONObject(installedIndex).optString("displayName", currentVersion)
            } else {
                currentVersion
            }

            return ModUpdate(
                modId = modId,
                modName = latest.getString("fileName"),
                currentVersion = resolvedCurrentVersion,
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                fileName = fileName,
                needsUpdate = needsUpdate
            )
        }
    }
}
