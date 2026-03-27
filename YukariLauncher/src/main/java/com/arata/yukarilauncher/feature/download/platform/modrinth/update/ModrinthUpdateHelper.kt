package com.arata.yukarilauncher.feature.download.platform.update

import android.os.Handler
import android.os.Looper
import com.arata.yukarilauncher.feature.download.platform.curseforge.update.CurseForgeUpdateHelper
import com.arata.yukarilauncher.feature.download.platform.modrinth.update.ModrinthUpdateHelper
import java.io.File
import java.util.concurrent.Executors

object ModUpdateChecker {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkUpdatesAsync(modsDir: File, minecraftVersion: String, onComplete: (List<ModUpdate>) -> Unit) {
        executor.execute {
            val installedMods = InstalledModsScanner.scan(modsDir)
            val updates = mutableListOf<ModUpdate>()

            installedMods.forEach { mod ->
                when (mod.loader.lowercase()) {
                    "fabric" -> {
                        val modrinth = ModrinthUpdateHelper.checkUpdate(mod.modId, mod.version, minecraftVersion, mod.loader.lowercase())
                        if (modrinth?.needsUpdate == true) updates.add(modrinth)
                    }
                    "forge", "neoforge" -> {
                        val curseforge = CurseForgeUpdateHelper.checkUpdate(mod.modId, mod.version, minecraftVersion)
                        if (curseforge?.needsUpdate == true) updates.add(curseforge)
                    }
                }
            }

            mainHandler.post { onComplete(updates) }
        }
    }
}