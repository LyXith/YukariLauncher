package com.arata.yukarilauncher.feature.download.platform.modrinth.update

import com.arata.yukarilauncher.feature.download.platform.update.ModUpdate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object ModrinthUpdateHelper {
    private val client = OkHttpClient()

    fun checkUpdate(projectIdOrSlug: String, currentVersion: String, minecraftVersion: String, loader: String? = null): ModUpdate? {
        val request = Request.Builder()
            .url("https://api.modrinth.com/v2/project/$projectIdOrSlug/version")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val versions = JSONArray(response.body?.string() ?: return null)
            if (versions.length() == 0) return null

            // Find the latest version compatible with the given Minecraft version and loader
            val compatibleVersions = mutableListOf<JSONObject>()
            for (i in 0 until versions.length()) {
                val version = versions.getJSONObject(i)
                // Check game version
                val gameVersions = version.getJSONArray("game_versions")
                var gameVersionOk = false
                for (j in 0 until gameVersions.length()) {
                    if (gameVersions.getString(j) == minecraftVersion) {
                        gameVersionOk = true
                        break
                    }
                }
                if (!gameVersionOk) continue

                // Optionally check loader
                if (loader != null) {
                    val loaders = version.getJSONArray("loaders")
                    var loaderOk = false
                    for (j in 0 until loaders.length()) {
                        if (loaders.getString(j).equals(loader, ignoreCase = true)) {
                            loaderOk = true
                            break
                        }
                    }
                    if (!loaderOk) continue
                }

                compatibleVersions.add(version)
            }
            if (compatibleVersions.isEmpty()) return null

            // Assume list is sorted newest first
            val latest = compatibleVersions[0]
            val latestVersion = latest.getString("version_number")
            val downloadUrl = latest.getJSONArray("files").getJSONObject(0).getString("url")

            return ModUpdate(
                modId = projectIdOrSlug,
                modName = latest.getString("name"),
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                needsUpdate = currentVersion != latestVersion
            )
        }
    }
}