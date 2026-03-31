package com.arata.yukarilauncher.feature.mod.modpack.export

import com.arata.yukarilauncher.feature.version.Version
import com.arata.yukarilauncher.utils.file.FileTools
import com.arata.yukarilauncher.feature.customprofilepath.ProfilePathHome
import net.kdt.pojavlaunch.Tools
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
            val includedFiles = collectIncludedFiles(gameDir, options)

            ZipOutputStream(FileOutputStream(exportFile)).use { zos ->
                when (exportType) {
                    ExportType.MODRINTH -> {
                        val indexedFiles = selectModrinthIndexedFiles(includedFiles)
                        val index = buildModrinthIndex(version, dependencies, options, indexedFiles)
                        writeJsonEntry(zos, "modrinth.index.json", index)
                        val overrideFiles = includedFiles.filterNot { filePair -> indexedFiles.any { it.first == filePair.first } }
                        zipOverrides(zos, overrideFiles)
                    }

                    ExportType.CURSEFORGE -> {
                        val manifestFiles = selectCurseManifestFiles(includedFiles)
                        val manifest = buildCurseManifest(version, dependencies, options, manifestFiles)
                        writeJsonEntry(zos, "manifest.json", manifest)
                        writeHtmlEntry(zos, "modlist.html", buildModListHtml(includedFiles))
                        val overrideFiles = includedFiles.filterNot { filePair -> manifestFiles.any { it.first == filePair.first } }
                        zipOverrides(zos, overrideFiles)
                    }
                }
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

        private fun collectIncludedFiles(gameDir: File, options: ExportOptions): List<Pair<String, File>> {
            return gameDir.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    gameDir.toPath().relativize(file.toPath()).toString().replace('\\', '/') to file
                }
                .filter { (path, _) -> shouldInclude(path, options) }
                .toList()
        }

        private fun zipOverrides(zos: ZipOutputStream, overrideFiles: List<Pair<String, File>>) {
            overrideFiles.forEach { (path, file) ->
                FileTools.zipFile(file, "overrides/$path", zos)
            }
        }

        private fun selectModrinthIndexedFiles(includedFiles: List<Pair<String, File>>): List<Pair<String, File>> {
            return includedFiles.filter { (path, file) ->
                path.startsWith("mods/") && file.extension.equals("jar", ignoreCase = true)
            }
        }

        private fun selectCurseManifestFiles(includedFiles: List<Pair<String, File>>): List<Pair<String, File>> {
            return includedFiles.filter { (path, file) ->
                if (!path.startsWith("mods/")) return@filter false
                val ids = parseCurseIds(file.name)
                ids.first != 0L && ids.second != 0L
            }
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

        private fun buildModrinthIndex(
            version: Version,
            dependencies: Map<String, String>,
            options: ExportOptions,
            indexedFiles: List<Pair<String, File>>
        ): Map<String, Any> {
            val files = indexedFiles.map { (path, file) ->
                mapOf(
                    "path" to path,
                    "hashes" to mapOf(
                        "sha1" to FileTools.calculateFileHash(file, "SHA-1"),
                        "sha512" to FileTools.calculateFileHash(file, "SHA-512")
                    ),
                    "env" to mapOf(
                        "client" to "required",
                        "server" to "required"
                    ),
                    "downloads" to listOf(file.toURI().toString()),
                    "fileSize" to file.length()
                )
            }
            return mapOf(
                "formatVersion" to 1,
                "game" to "minecraft",
                "versionId" to (options.packVersion ?: version.getVersionName()),
                "name" to (options.packName ?: version.getVersionName()),
                "files" to files,
                "dependencies" to dependencies
            )
        }

        private fun buildCurseManifest(
            versionObj: Version,
            dependencies: Map<String, String>,
            options: ExportOptions,
            manifestFiles: List<Pair<String, File>>
        ): Map<String, Any> {
            val curseFiles = manifestFiles
                .map { (path, file) ->
                    val parsedIds = parseCurseIds(file.name)
                    mapOf(
                        "projectID" to parsedIds.first,
                        "fileID" to parsedIds.second,
                        "required" to true,
                        "isLocked" to false,
                        "path" to path
                    )
                }

            val modLoaders = dependencies.entries
                .filter { it.key != "minecraft" }
                .map { mapOf("id" to "${it.key}-${it.value}", "primary" to true) }

            return mapOf(
                "minecraft" to mapOf(
                    "version" to (dependencies["minecraft"] ?: versionObj.getVersionName()),
                    "modLoaders" to modLoaders
                ),
                "manifestType" to "minecraftModpack",
                "manifestVersion" to 1,
                "name" to (options.packName ?: versionObj.getVersionName()),
                "version" to (options.packVersion ?: "1.0.0"),
                "author" to (options.author ?: "YukariLauncher"),
                "files" to curseFiles,
                "overrides" to "overrides"
            )
        }

        private fun writeJsonEntry(zos: ZipOutputStream, entryName: String, obj: Any) {
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(Tools.GLOBAL_GSON.toJson(obj).toByteArray())
            zos.closeEntry()
        }

        private fun writeHtmlEntry(zos: ZipOutputStream, entryName: String, html: String) {
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(html.toByteArray())
            zos.closeEntry()
        }

        private fun parseCurseIds(fileName: String): Pair<Long, Long> {
            val numbers = Regex("(\\d+)").findAll(fileName).map { it.value.toLong() }.toList()
            return if (numbers.size >= 2) numbers[numbers.size - 2] to numbers.last() else 0L to 0L
        }

        private fun buildModListHtml(includedFiles: List<Pair<String, File>>): String {
            val mods = includedFiles.filter { (path, _) -> path.startsWith("mods/") }
            val listItems = mods.joinToString("\n") { (_, file) ->
                val modName = file.nameWithoutExtension
                val slug = modName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                """<li><a href="https://www.curseforge.com/minecraft/mc-mods/$slug">$modName</a></li>"""
            }
            return "<ul>\n$listItems\n</ul>"
        }
    }
}
