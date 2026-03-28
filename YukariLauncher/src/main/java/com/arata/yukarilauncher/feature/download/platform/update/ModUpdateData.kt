package com.arata.yukarilauncher.feature.download.platform.update

import java.io.File

data class ModUpdateData(
    val localFile: File,
    val modId: String,
    val modName: String,
    val currentVersion: String,
    val loader: String,
    val platform: String, // "modrinth" or "curseforge"
    val update: ModUpdate? // null if no update available
)