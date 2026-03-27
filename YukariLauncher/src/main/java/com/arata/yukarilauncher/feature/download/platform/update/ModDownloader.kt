package com.arata.yukarilauncher.feature.download.platform.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object ModDownloader {
    private val client = OkHttpClient()

    fun download(url: String, modName: String, versionDir: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("Download failed: HTTP ${response.code}")
                return
            }
            val bytes = response.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                println("Downloaded file is empty or null")
                return
            }

            val modsDir = File(versionDir, "mods")
            if (!modsDir.exists()) modsDir.mkdirs()

            val outFile = File(modsDir, modName)
            FileOutputStream(outFile).use { fos -> fos.write(bytes) }
        }
    }
}