package com.arata.yukarilauncher.feature.unpack

import android.content.Context
import android.content.res.AssetManager
import com.arata.yukarilauncher.feature.log.Logging.i
import com.arata.yukarilauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileInputStream

class UnpackComponentsTask(val context: Context, val component: Components) : AbstractUnpackTask() {
    private lateinit var am: AssetManager
    private lateinit var rootDir: String
    private lateinit var versionFile: File
    private var isCheckFailed: Boolean = false

    init {
        runCatching {
            am = context.assets
            rootDir = if (component.privateDirectory) PathManager.DIR_DATA else PathManager.DIR_GAME_HOME
            versionFile = File("$rootDir/${component.component}/version")
            // Just check that the version file exists in assets – we'll read it later
        }.getOrElse {
            isCheckFailed = true
        }
    }

    fun isCheckFailed() = isCheckFailed

    override fun isNeedUnpack(): Boolean {
        if (isCheckFailed) return false

        if (!versionFile.exists()) {
            requestEmptyParentDir(versionFile)
            i("Unpack Components", "${component.component}: Pack was installed manually, or does not exist...")
            return true
        } else {
            val fis = FileInputStream(versionFile)
            // Read version from assets and from destination
            val release1 = Tools.read(am.open("components/${component.component}/version"))
            val release2 = Tools.read(fis)
            if (release1 != release2) {
                requestEmptyParentDir(versionFile)
                return true
            } else {
                i("UnpackPrep", "${component.component}: Pack is up-to-date with the launcher, continuing...")
                return false
            }
        }
    }

    override fun run() {
        listener?.onTaskStart()
        val assetPath = "components/${component.component}"
        val destDir = File(rootDir, component.component)
        // Recursively copy all assets to destination
        copyAssetsRecursively(am, assetPath, destDir)
        listener?.onTaskEnd()
    }

    /**
     * Recursively copies all files from a given asset directory to a destination folder.
     *
     * @param assetManager The Android AssetManager.
     * @param assetPath    The path inside assets (e.g. "components/lwjgl3").
     * @param destDir      The destination directory (e.g. "/storage/emulated/0/YukariLauncher/lwjgl3").
     */
    private fun copyAssetsRecursively(assetManager: AssetManager, assetPath: String, destDir: File) {
        val entries = assetManager.list(assetPath) ?: return
        destDir.mkdirs()
        for (entry in entries) {
            val srcPath = "$assetPath/$entry"
            val destFile = File(destDir, entry)
            val subEntries = assetManager.list(srcPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                // It's a directory – recurse
                copyAssetsRecursively(assetManager, srcPath, destFile)
            } else {
                // It's a file – copy it
                assetManager.open(srcPath).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun requestEmptyParentDir(file: File) {
        file.parentFile!!.apply {
            if (exists() && isDirectory) {
                FileUtils.deleteDirectory(this)
            }
            mkdirs()
        }
    }
}