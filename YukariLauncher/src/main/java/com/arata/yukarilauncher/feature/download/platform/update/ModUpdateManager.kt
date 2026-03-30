package com.arata.yukarilauncher.feature.download.platform.update

import android.content.Context
import com.arata.yukarilauncher.feature.download.platform.curseforge.update.CurseForgeUpdateHelper
import com.arata.yukarilauncher.feature.download.platform.modrinth.update.ModrinthUpdateHelper
import com.arata.yukarilauncher.feature.log.Logging
import com.arata.yukarilauncher.task.TaskExecutors
import java.io.File

object ModUpdateManager {

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
                Logging.i("ModUpdate", "Checking updates for Minecraft version: $minecraftVersion")
                val installedMods = InstalledModsScanner.scan(modsDir)
                Logging.i("ModUpdate", "Found ${installedMods.size} mods")
                val updates = mutableListOf<ModUpdate>()

                installedMods.forEachIndexed { index, mod ->
                    TaskExecutors.runInUIThread {
                        onProgress(index + 1, installedMods.size, mod.modName)
                    }

                    try {
                        Logging.i("ModUpdate", "Checking ${mod.modName} (${mod.modId}) loader: ${mod.loader} version: ${mod.version}")
                        val update = when (mod.loader.lowercase()) {
                            "fabric" -> {
                                Logging.i("ModUpdate", "Using Modrinth for ${mod.modName}")
                                ModrinthUpdateHelper.checkUpdate(
                                    mod.modId,
                                    mod.version,
                                    minecraftVersion,
                                    mod.loader.lowercase(),
                                    mod.file.name
                                )
                            }
                            "forge", "neoforge" -> {
                                Logging.i("ModUpdate", "Trying Modrinth for ${mod.modName}")
                                var update = ModrinthUpdateHelper.checkUpdate(
                                    mod.modId,
                                    mod.version,
                                    minecraftVersion,
                                    mod.loader.lowercase(),
                                    mod.file.name
                                )
                                if (update == null && mod.modId.toLongOrNull() != null) {
                                    Logging.i("ModUpdate", "Modrinth failed, trying CurseForge for ${mod.modName}")
                                    update = CurseForgeUpdateHelper.checkUpdate(
                                        mod.modId,
                                        mod.version,
                                        minecraftVersion,
                                        mod.file.name
                                    )
                                }
                                update
                            }
                            else -> {
                                Logging.i("ModUpdate", "Unsupported loader: ${mod.loader} for ${mod.modName}")
                                null
                            }
                        }

                        if (update?.needsUpdate == true) {
                            Logging.i("ModUpdate", "Update available for ${mod.modName}: ${update.latestVersion}")
                            updates.add(update.copy(originalFile = mod.file))
                        } else {
                            Logging.i("ModUpdate", "No update for ${mod.modName}")
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
                    val targetFile = File(modsDir, update.fileName)

                    if (targetFile.exists()) {
                        successCount++
                        TaskExecutors.runInUIThread {
                            onProgress(index + 1, total, update.fileName, 100)
                        }
                        // Delete old file if it exists and is different
                        update.originalFile?.takeIf { file: File -> file.exists() && file != targetFile }?.delete()
                        return@forEachIndexed
                    }

                    var lastPercent = 0
                    ModDownloader.downloadWithProgress(
                        url = update.downloadUrl,
                        outputFile = targetFile,
                        onProgress = { percent ->
                            if (percent > lastPercent) {
                                lastPercent = percent
                                TaskExecutors.runInUIThread {
                                    onProgress(index + 1, total, update.fileName, percent)
                                }
                            }
                        }
                    )
                    // Delete old file after successful download
                    update.originalFile?.takeIf { file: File -> file.exists() && file != targetFile }?.delete()
                    successCount++
                    Logging.i("ModUpdate", "Downloaded ${update.fileName}")
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
