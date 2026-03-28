package com.arata.yukarilauncher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.arata.anim.AnimPlayer
import com.arata.anim.animations.Animations
import com.arata.yukarilauncher.R
import com.arata.yukarilauncher.databinding.FragmentVersionManagerBinding
import com.arata.yukarilauncher.feature.download.platform.curseforge.update.CurseForgeUpdateHelper
import com.arata.yukarilauncher.feature.download.platform.modrinth.update.ModrinthUpdateHelper
import com.arata.yukarilauncher.feature.download.platform.update.InstalledModsScanner
import com.arata.yukarilauncher.feature.download.platform.update.ModDownloader
import com.arata.yukarilauncher.feature.download.platform.update.ModUpdate
import com.arata.yukarilauncher.feature.log.Logging
import com.arata.yukarilauncher.feature.version.NoVersionException
import com.arata.yukarilauncher.feature.version.Version
import com.arata.yukarilauncher.feature.version.VersionsManager
import com.arata.yukarilauncher.task.Task
import com.arata.yukarilauncher.task.TaskExecutors
import com.arata.yukarilauncher.ui.dialog.TipDialog
import com.arata.yukarilauncher.utils.ZHTools
import com.arata.yukarilauncher.utils.file.FileDeletionHandler
import net.kdt.pojavlaunch.Tools
import java.io.File

class VersionManagerFragment : FragmentWithAnim(R.layout.fragment_version_manager), View.OnClickListener {
    companion object {
        const val TAG: String = "VersionManagerFragment"
    }

    private lateinit var binding: FragmentVersionManagerBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentVersionManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val fragment = this
        binding.apply {
            shortcutsMods.setOnClickListener(fragment)
            gamePath.setOnClickListener(fragment)
            resourcePath.setOnClickListener(fragment)
            worldPath.setOnClickListener(fragment)
            shaderPath.setOnClickListener(fragment)
            screenshotPath.setOnClickListener(fragment)
            logsPath.setOnClickListener(fragment)
            crashReportPath.setOnClickListener(fragment)
            versionSettings.setOnClickListener(fragment)
            versionRename.setOnClickListener(fragment)
            versionCopy.setOnClickListener(fragment)
            versionDelete.setOnClickListener(fragment)
            checkUpdates.setOnClickListener(fragment)
        }
    }

    private fun File.mustExists(): File {
        if (!exists()) mkdirs()
        return this
    }

    private fun swapFilesFragment(lockPath: File, listPath: File) {
        val bundle = Bundle().apply {
            putString(FilesFragment.BUNDLE_LOCK_PATH, lockPath.mustExists().absolutePath)
            putString(FilesFragment.BUNDLE_LIST_PATH, listPath.mustExists().absolutePath)
            putBoolean(FilesFragment.BUNDLE_QUICK_ACCESS_PATHS, false)
        }
        ZHTools.swapFragmentWithAnim(this, FilesFragment::class.java, FilesFragment.TAG, bundle)
    }

    override fun onClick(v: View) {
        val activity = requireActivity()
        val version = VersionsManager.getCurrentVersion() ?: run {
            Tools.showError(activity, getString(R.string.version_manager_no_installed_version), NoVersionException("No installed version"))
            return
        }
        val gameDir = version.getGameDir()

        binding.apply {
            when (v) {
                shortcutsMods -> {
                    val bundle = Bundle().apply {
                        putString(ModsFragment.BUNDLE_ROOT_PATH, File(gameDir, "mods").mustExists().absolutePath)
                    }
                    ZHTools.swapFragmentWithAnim(this@VersionManagerFragment, ModsFragment::class.java, ModsFragment.TAG, bundle)
                }
                gamePath -> swapFilesFragment(gameDir, gameDir)
                resourcePath -> swapFilesFragment(gameDir, File(gameDir, "resourcepacks"))
                worldPath -> swapFilesFragment(gameDir, File(gameDir, "saves"))
                shaderPath -> swapFilesFragment(gameDir, File(gameDir, "shaderpacks"))
                screenshotPath -> swapFilesFragment(gameDir, File(gameDir, "screenshots"))
                logsPath -> swapFilesFragment(gameDir, File(gameDir, "logs"))
                crashReportPath -> swapFilesFragment(gameDir, File(gameDir, "crash-reports"))

                versionSettings -> ZHTools.swapFragmentWithAnim(this@VersionManagerFragment, VersionConfigFragment::class.java, VersionConfigFragment.TAG, null)
                versionRename -> VersionsManager.openRenameDialog(activity, version) {
                    Tools.backToMainMenu(activity)
                }
                versionCopy -> VersionsManager.openCopyDialog(activity, version)
                versionDelete -> {
                    TipDialog.Builder(activity)
                        .setTitle(R.string.generic_warning)
                        .setMessage(activity.getString(R.string.version_manager_delete_tip, version.getVersionName()))
                        .setWarning()
                        .setConfirmClickListener {
                            FileDeletionHandler(
                                activity,
                                listOf(version.getVersionPath()),
                                Task.runTask {
                                    VersionsManager.refresh("VersionManagerFragment:versionDelete")
                                }.ended(TaskExecutors.getAndroidUI()) {
                                    Tools.backToMainMenu(activity)
                                }
                            ).start()
                        }
                        .showDialog()
                }

                checkUpdates -> {
                    binding.checkUpdates.isEnabled = false
                    val toast = Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT)
                    toast.show()

                    TaskExecutors.getDefault().execute {
                        try {
                            val modsDir = File(gameDir, "mods").apply {
                                if (!exists()) mkdirs()
                            }
                            Logging.i("ModUpdate", "Scanning mods in: $modsDir")

                            val minecraftVersion = version.getVersionName()
                            Logging.i("ModUpdate", "Minecraft version: $minecraftVersion")

                            val installedMods = runCatching { InstalledModsScanner.scan(modsDir) }
                                .getOrElse { emptyList() }
                            Logging.i("ModUpdate", "Found ${installedMods.size} mods")

                            val updates = mutableListOf<ModUpdate>()

                            installedMods.forEach { mod ->
                                Logging.i("ModUpdate", "Checking ${mod.modName} (${mod.modId}) loader: ${mod.loader} version: ${mod.version}")
                                runCatching {
                                    val update = when (mod.loader.lowercase()) {
                                        "fabric" -> {
                                            Logging.i("ModUpdate", "Using Modrinth for ${mod.modName}")
                                            ModrinthUpdateHelper.checkUpdate(mod.modId, mod.version, minecraftVersion, mod.loader.lowercase())
                                        }
                                        "forge", "neoforge" -> {
                                            Logging.i("ModUpdate", "Using CurseForge for ${mod.modName}")
                                            CurseForgeUpdateHelper.checkUpdate(mod.modId, mod.version, minecraftVersion)
                                        }
                                        else -> null
                                    }
                                    if (update?.needsUpdate == true) {
                                        Logging.i("ModUpdate", "Update available for ${mod.modName}: ${update.latestVersion}")
                                        updates.add(update)
                                    } else {
                                        Logging.i("ModUpdate", "No update for ${mod.modName} (current: ${mod.version})")
                                    }
                                }.onFailure { e ->
                                    Logging.e("ModUpdate", "Failed to check ${mod.modName}: ${e.message}", e)
                                }
                            }

                            TaskExecutors.runInUIThread {
                                binding.checkUpdates.isEnabled = true
                                toast.cancel()
                                if (updates.isEmpty()) {
                                    Toast.makeText(activity, "All mods are up to date!", Toast.LENGTH_LONG).show()
                                } else {
                                    AlertDialog.Builder(activity)
                                        .setTitle("Mod Updates Available")
                                        .setMessage(updates.joinToString(separator = "\n") { update: ModUpdate ->
                                            "${update.modName} → ${update.latestVersion}"
                                        })
                                        .setPositiveButton("Update All") { _, _ ->
                                            TaskExecutors.getDefault().execute {
                                                updates.forEach { update: ModUpdate ->
                                                    runCatching {
                                                        Logging.i("ModUpdate", "Downloading ${update.modName} from ${update.downloadUrl}")
                                                        val fileName = "${update.modName}-${update.latestVersion}.jar".replace("/", "_").replace(" ", "_")
                                                        ModDownloader.download(update.downloadUrl, fileName, gameDir)
                                                        Logging.i("ModUpdate", "Downloaded ${update.modName}")
                                                    }.onFailure { e ->
                                                        Logging.e("ModUpdate", "Failed to download ${update.modName}: ${e.message}", e)
                                                    }
                                                }
                                                TaskExecutors.runInUIThread {
                                                    Toast.makeText(activity, "Updates completed", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                }
                            }
                        } catch (e: Throwable) {
                            Logging.e("ModUpdate", "Update check failed", e)
                            TaskExecutors.runInUIThread {
                                binding.checkUpdates.isEnabled = true
                                toast.cancel()
                                Tools.showError(activity, "Mod update check failed: ${e.message ?: "Unknown error"}", e)
                            }
                        }
                    }
                }

                else -> {}
            }
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(shortcutsLayout, Animations.BounceInRight))
                .apply(AnimPlayer.Entry(editLayout, Animations.BounceInLeft))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(shortcutsLayout, Animations.FadeOutLeft))
                .apply(AnimPlayer.Entry(editLayout, Animations.FadeOutRight))
        }
    }
}