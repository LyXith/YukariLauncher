package com.arata.yukarilauncher.feature.download.platform.update

import android.content.Context
import com.arata.yukarilauncher.R
import com.arata.yukarilauncher.feature.download.platform.curseforge.update.CurseForgeUpdateHelper
import com.arata.yukarilauncher.feature.download.platform.modrinth.update.ModrinthUpdateHelper
import com.arata.yukarilauncher.feature.log.Logging
import com.arata.yukarilauncher.task.TaskExecutors
import java.io.File

object ModUpdateManager {

    /**
     * Check for updates for all mods in a given directory.
     *
     * @param context           Context for resources
     * @param modsDir           Directory containing mod JARs
     * @param minecraftVersion  Current Minecraft version (e.g., "1.21.11")
     * @param onProgress        Called on UI thread with (current, total, modName)
     * @param onComplete        Called on UI thread with list of updates (non‑empty if any)
     * @param onError           Called on UI thread with any exception
     */
    fun checkUpdates(
        context: Context,
        modsDir: File,
        minecraftVersion: String,
        onProgress: (Int, Int, String) -> Unit,
        onComplete: (List<ModUpdate>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        TaskExecutors.getDefault().execute {
            try {
                val installedMods = InstalledModsScanner.scan(modsDir)
                val updates = mutableListOf<ModUpdate>()

                installedMods.forEachIndexed { index, mod ->
                    // Report progress
                    TaskExecutors.runInUIThread {
                        onProgress(index + 1, installedMods.size, mod.modName)
                    }

                    try {
                        val update = when (mod.loader.lowercase()) {
                            "fabric" -> ModrinthUpdateHelper.checkUpdate(
                                mod.modId,
                                mod.version,
                                minecraftVersion,
                                mod.loader.lowercase()
                            )
                            "forge", "neoforge" -> CurseForgeUpdateHelper.checkUpdate(
                                mod.modId,
                                mod.version,
                                minecraftVersion
                            )
                            else -> null
                        }
                        if (update?.needsUpdate == true) {
                            updates.add(update)
                        }
                    } catch (e: Exception) {
                        Logging.e("ModUpdate", "Failed to check ${mod.modName}", e)
                    }
                }

                TaskExecutors.runInUIThread {
                    onComplete(updates)
                }
            } catch (e: Exception) {
                Logging.e("ModUpdate", "Update check failed", e)
                TaskExecutors.runInUIThread {
                    onError(e)
                }
            }
        }
    }

    /**
     * Download and install the given updates.
     *
     * @param context      Context for resources
     * @param updates      List of updates to download
     * @param gameDir      Game directory (where the 'mods' folder resides)
     * @param onProgress   Called on UI thread with (current, total, fileName, progressPercent)
     * @param onComplete   Called on UI thread when all downloads are done
     * @param onError      Called on UI thread if any error occurs
     */
    fun applyUpdates(
        context: Context,
        updates: List<ModUpdate>,
        gameDir: File,
        onProgress: (Int, Int, String, Int) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (updates.isEmpty()) {
            onComplete()
            return
        }

        TaskExecutors.getDefault().execute {
            try {
                val modsDir = File(gameDir, "mods")
                if (!modsDir.exists()) modsDir.mkdirs()

                var successCount = 0
                val total = updates.size

                updates.forEachIndexed { index, update ->
                    val fileName = "${update.modName}-${update.latestVersion}.jar"
                        .replace("/", "_")
                        .replace(" ", "_")
                    val targetFile = File(modsDir, fileName)

                    // Avoid duplicate if already downloaded
                    if (targetFile.exists()) {
                        successCount++
                        TaskExecutors.runInUIThread {
                            onProgress(index + 1, total, fileName, 100)
                        }
                        return@forEachIndexed
                    }

                    // Download with progress
                    var lastPercent = 0
                    ModDownloader.downloadWithProgress(
                        url = update.downloadUrl,
                        outputFile = targetFile,
                        onProgress = { percent ->
                            if (percent > lastPercent) {
                                lastPercent = percent
                                TaskExecutors.runInUIThread {
                                    onProgress(index + 1, total, fileName, percent)
                                }
                            }
                        }
                    )
                    successCount++
                }

                TaskExecutors.runInUIThread {
                    onComplete()
                }
            } catch (e: Exception) {
                Logging.e("ModUpdate", "Apply updates failed", e)
                TaskExecutors.runInUIThread {
                    onError(e)
                }
            }
        }
    }
}