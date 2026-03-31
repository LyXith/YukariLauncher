package com.arata.yukarilauncher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.arata.anim.AnimPlayer
import com.arata.anim.animations.Animations
import com.arata.yukarilauncher.R
import com.arata.yukarilauncher.databinding.FragmentVersionManagerBinding
import com.arata.yukarilauncher.feature.download.platform.update.ModUpdate
import com.arata.yukarilauncher.feature.download.platform.update.ModUpdateManager
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

    /**
     * Extracts the pure Minecraft version (without loader suffix) from a Version object.
     * Falls back to stripping common suffixes like " Fabric", " Forge", " NeoForge".
     */
    private fun getGameVersion(version: Version): String {
        val versionInfo = version.getVersionInfo()
        if (versionInfo != null && versionInfo.minecraftVersion.isNotBlank()) {
            return versionInfo.minecraftVersion
        }
        // Fallback: strip known suffixes
        val rawName = version.getVersionName()
        return rawName
            .replace(" Fabric", "")
            .replace(" Forge", "")
            .replace(" NeoForge", "")
            .trim()
    }

    private fun createProgressDialog(title: String): Triple<AlertDialog, TextView, ProgressBar> {
        val activity = requireActivity()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val messageView = TextView(activity).apply {
            textSize = 14f
        }
        val progressBar = ProgressBar(
            activity,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }
        container.addView(messageView)
        container.addView(progressBar)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .create()

        return Triple(dialog, messageView, progressBar)
    }

    private fun showUpdateSelectionDialog(activity: android.app.Activity, updates: List<ModUpdate>, gameDir: File) {
        val labels = updates.map { "${it.modName}: ${it.currentVersion} → ${it.latestVersion}" }.toTypedArray()
        val checked = BooleanArray(updates.size) { true }

        AlertDialog.Builder(activity)
            .setTitle("Select mods to update")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Continue") { _, _ ->
                val selectedUpdates = updates.filterIndexed { index, _ -> checked[index] }
                if (selectedUpdates.isEmpty()) {
                    Toast.makeText(activity, "No mods selected for update.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val summary = selectedUpdates.joinToString(separator = "\n") {
                    "${it.modName} → ${it.latestVersion}"
                }

                AlertDialog.Builder(activity)
                    .setTitle("Confirm updates")
                    .setMessage("Update ${selectedUpdates.size} selected mods?\n\n$summary")
                    .setPositiveButton("Update") { _, _ ->
                        val (downloadDialog, messageView, progressBar) = createProgressDialog("Updating mods")
                        downloadDialog.show()
                        messageView.text = "Preparing downloads..."

                        ModUpdateManager.applyUpdates(
                            context = activity,
                            updates = selectedUpdates,
                            gameDir = gameDir,
                            onProgress = { current, total, fileName, percent ->
                                val overallPercent = (((current - 1) * 100) + percent) / total
                                progressBar.progress = overallPercent
                                messageView.text = "Downloading ($current/$total)\n$fileName ($percent%)"
                            },
                            onComplete = {
                                downloadDialog.dismiss()
                                Toast.makeText(activity, "Selected updates completed.", Toast.LENGTH_LONG).show()
                            },
                            onError = { e ->
                                downloadDialog.dismiss()
                                Tools.showError(activity, "Update failed: ${e.message}", e)
                            }
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

                // ============================================================
                // MOD UPDATE CHECKER – using the improved ModUpdateManager
                // ============================================================
                checkUpdates -> {
                    binding.checkUpdates.isEnabled = false
                    val (checkingDialog, messageView, progressBar) = createProgressDialog("Checking mod updates")
                    checkingDialog.show()
                    messageView.text = "Scanning mods..."

                    val modsDir = File(gameDir, "mods").apply { if (!exists()) mkdirs() }
                    val minecraftVersion = getGameVersion(version)
                    Logging.i("ModUpdate", "Using game version: $minecraftVersion")

                    ModUpdateManager.checkUpdates(
                        context = activity,
                        modsDir = modsDir,
                        minecraftVersion = minecraftVersion,
                        onProgress = { current, total, modName ->
                            progressBar.progress = if (total == 0) 0 else current * 100 / total
                            messageView.text = "Checking ($current/$total)\n$modName"
                        },
                        onComplete = { updates ->
                            checkingDialog.dismiss()
                            binding.checkUpdates.isEnabled = true

                            if (updates.isEmpty()) {
                                Toast.makeText(activity, "All mods are up to date!", Toast.LENGTH_LONG).show()
                            } else {
                                showUpdateSelectionDialog(activity, updates, gameDir)
                            }
                        },
                        onError = { e ->
                            checkingDialog.dismiss()
                            binding.checkUpdates.isEnabled = true
                            Tools.showError(activity, "Update check failed: ${e.message}", e)
                        }
                    )
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
