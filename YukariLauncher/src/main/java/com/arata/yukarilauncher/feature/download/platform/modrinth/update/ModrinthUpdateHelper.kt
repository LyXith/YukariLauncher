package com.arata.yukarilauncher.feature.download.platform.modrinth.update

import com.arata.yukarilauncher.feature.download.platform.update.ModUpdate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object ModrinthUpdateHelper {
    private val client = OkHttpClient()

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

    private fun hasFileName(version: JSONObject, fileName: String): Boolean {
        val files = version.optJSONArray("files") ?: return false
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            if (fileName.equals(file.optString("filename"), ignoreCase = true)) return true
        }
        return false
    }

    fun checkUpdate(
        projectIdOrSlug: String,
        currentVersion: String,
        minecraftVersion: String,
        loader: String? = null,
        currentFileName: String? = null
    ): ModUpdate? {
        val request = Request.Builder()
            .url("https://api.modrinth.com/v2/project/$projectIdOrSlug/version")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val versions = JSONArray(response.body?.string() ?: return null)
            if (versions.length() == 0) return null

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

            // The API returns versions sorted by date descending (newest first)
            val latest = compatibleVersions[0]
            val latestVersion = latest.getString("version_number")
            val latestVersionId = latest.getString("id")
            val files = latest.getJSONArray("files")
            val firstFile = files.getJSONObject(0)
            val downloadUrl = firstFile.getString("url")
            val fileName = firstFile.getString("filename")  // e.g., "sodium-fabric-0.8.7+mc1.21.11.jar"
            val installedByFile = currentFileName?.let { installedName ->
                compatibleVersions.firstOrNull { version -> hasFileName(version, installedName) }
            }
            val needsUpdate = when {
                installedByFile != null -> installedByFile.getString("id") != latestVersionId
                else -> !versionsEquivalent(currentVersion, latestVersion)
            }
            val resolvedCurrentVersion = installedByFile?.optString("version_number", currentVersion) ?: currentVersion

            return ModUpdate(
                modId = projectIdOrSlug,
                modName = latest.getString("name"),
                currentVersion = resolvedCurrentVersion,
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                fileName = fileName,
                needsUpdate = needsUpdate
            )
        }
    }
}
