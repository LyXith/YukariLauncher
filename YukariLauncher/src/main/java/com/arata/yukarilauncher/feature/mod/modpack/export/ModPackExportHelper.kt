package com.arata.yukarilauncher.feature.mod.modpack.export

import com.arata.yukarilauncher.feature.version.Version
import com.arata.yukarilauncher.utils.file.FileTools
import com.arata.yukarilauncher.feature.customprofilepath.ProfilePathHome
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModPackExportHelper {
    enum class ExportType {
        MODRINTH,
        CURSEFORGE
    }

    data class ExportOptions(
        val includePaths: Set<String> = emptySet(),
        val excludePaths: Set<String> = setOf("logs", "crash-reports"),
        val packName: String? = null,
        val packVersion: String? = null,
        val author: String? = null
    )

    companion object {
        @JvmStatic
        fun export(version: Version, exportType: ExportType, options: ExportOptions = ExportOptions()): File {
            val gameDir = version.getGameDir()
            val exportDir = File(File(ProfilePathHome.getGameHome()).parentFile, "exported").apply { mkdirs() }
            val suffix = if (exportType == ExportType.MODRINTH) ".mrpack" else ".zip"
            val filenameVersion = (options.packVersion ?: version.getVersionName()).replace("/", "_")
            val exportFile = File(exportDir, "${version.getVersionName()}-$filenameVersion$suffix")
            val dependencies = buildDependencies(version)

            ZipOutputStream(FileOutputStream(exportFile)).use { zos ->
                when (exportType) {
                    ExportType.MODRINTH -> {
                        val index = buildModrinthIndex(version, dependencies, options)
                        writeJsonEntry(zos, "modrinth.index.json", index)
                    }

                    ExportType.CURSEFORGE -> {
                        val manifest = buildCurseManifest(version, dependencies, options)
                        writeJsonEntry(zos, "manifest.json", manifest)
                    }
                }

                FileTools.zipDirectory(gameDir, "overrides/", { file ->
                    val relativePath = gameDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    shouldInclude(relativePath, options)
                }, zos)
            }

            return exportFile
        }

        private fun shouldInclude(relativePath: String, options: ExportOptions): Boolean {
            if (relativePath.isBlank()) return false
            val normalizedPath = relativePath.trim('/')
            val include = options.includePaths
            val exclude = options.excludePaths

            if (exclude.any { normalizedPath == it || normalizedPath.startsWith("$it/") }) return false
            if (include.isEmpty()) return true
            return include.any { normalizedPath == it || normalizedPath.startsWith("$it/") }
        }

        private fun buildDependencies(version: Version): MutableMap<String, String> {
            val dependencies = mutableMapOf<String, String>()
            version.getVersionInfo()?.let { info ->
                dependencies["minecraft"] = info.minecraftVersion
                info.loaderInfo?.forEach { loader ->
                    val key = when (loader.name.lowercase(Locale.ROOT)) {
                        "forge" -> "forge"
                        "neoforge" -> "neoforge"
                        "fabric" -> "fabric-loader"
                        "quilt" -> "quilt-loader"
                        else -> null
                    }
                    if (key != null && loader.version.isNotBlank()) dependencies[key] = loader.version
                }
            }
            return dependencies
        }

        private fun buildModrinthIndex(version: Version, dependencies: Map<String, String>, options: ExportOptions): ModrinthIndex {
            return ModrinthIndex().apply {
                formatVersion = 1
                game = "minecraft"
                versionId = options.packVersion ?: version.getVersionName()
                name = options.packName ?: version.getVersionName()
                summary = "Exported from YukariLauncher"
                files = emptyArray()
                this.dependencies = dependencies
            }
        }

        private fun buildCurseManifest(versionObj: Version, dependencies: Map<String, String>, options: ExportOptions): CurseManifest {
            return CurseManifest().apply {
                name = options.packName ?: versionObj.getVersionName()
                this.version = options.packVersion ?: "1.0.0"
                author = options.author ?: "YukariLauncher"
                manifestType = "minecraftModpack"
                manifestVersion = 1
                files = emptyArray()
                overrides = "overrides"
                minecraft = CurseManifest.CurseMinecraft().apply {
                    version = dependencies["minecraft"] ?: versionObj.getVersionName()
                    modLoaders = dependencies.entries
                        .filter { it.key != "minecraft" }
                        .map {
                            CurseManifest.CurseModLoader().apply {
                                id = "${it.key}-${it.value}"
                                primary = true
                            }
                        }.toTypedArray()
                }
            }
        }

        private fun writeJsonEntry(zos: ZipOutputStream, entryName: String, obj: Any) {
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(Tools.GLOBAL_GSON.toJson(obj).toByteArray())
            zos.closeEntry()
        }
    }
}
