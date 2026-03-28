package com.arata.yukarilauncher.feature.download.platform.update

import com.arata.yukarilauncher.feature.log.Logging
import org.json.JSONObject
import java.io.File
import java.util.jar.JarFile

object ModMetadataReader {

    data class ModInfo(
        val modId: String,
        val modName: String,
        val version: String,
        val loader: String
    )

    private fun parseFabric(jar: File): ModInfo? {
        return try {
            JarFile(jar).use { jarFile ->
                val entry = jarFile.getJarEntry("fabric.mod.json") ?: return null
                val json = JSONObject(jarFile.getInputStream(entry).bufferedReader().readText())
                ModInfo(
                    modId = json.getString("id"),
                    modName = json.optString("name", json.getString("id")),
                    version = json.optString("version", "unknown"),
                    loader = "fabric"
                )
            }
        } catch (e: Exception) {
            Logging.e("ModMetadata", "Failed to parse Fabric mod ${jar.name}", e)
            null
        }
    }

    private fun parseForge(jar: File): ModInfo? {
        return try {
            JarFile(jar).use { jarFile ->
                val entry = jarFile.getJarEntry("META-INF/mods.toml") ?: return null
                val text = jarFile.getInputStream(entry).bufferedReader().readText()

                val modId = Regex("""modId\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: return null
                val version = Regex("""version\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "unknown"
                val displayName = Regex("""displayName\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: modId

                ModInfo(
                    modId = modId,
                    modName = displayName,
                    version = version,
                    loader = "forge"
                )
            }
        } catch (e: Exception) {
            Logging.e("ModMetadata", "Failed to parse Forge mod ${jar.name}", e)
            null
        }
    }

    fun parseMod(jar: File): ModInfo? {
        return parseFabric(jar) ?: parseForge(jar)
    }
}