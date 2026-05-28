/*
 * CoreManager.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.ext.feature.core

import android.content.Context
import android.content.SharedPreferences
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
) : CoreUpdater {

    companion object {
        // Libretro nightly buildbot – Android arm64-v8a
        private const val LIBRETRO_NIGHTLY_BASE =
            "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/"

        // Sub-folder inside the app's cores directory for nightly downloads
        private const val NIGHTLY_DIR = "nightly"
    }

    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

    override suspend fun downloadCores(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        val sharedPreferences =
            SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        coreIDs.asFlow()
            .onEach { retrieveAssets(it, sharedPreferences) }
            .onEach { downloadCoreFromLibretroNightly(it) }
            .collect()
    }

    private suspend fun retrieveAssets(
        coreID: CoreID,
        sharedPreferences: SharedPreferences,
    ) {
        CoreID.getAssetManager(coreID)
            .retrieveAssetsIfNeeded(api, directoriesManager, sharedPreferences)
    }

    /**
     * Downloads a core from the libretro nightly buildbot (arm64-v8a).
     *
     * URL pattern:
     *   https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/{coreName}_libretro_android.so.zip
     *
     * The zip contains one entry:  {coreName}_libretro_android.so
     * We extract and save it as:   lib{coreName}_libretro_android.so  (= coreID.libretroFileName)
     * which is the name LibretroDroid expects.
     */
    private suspend fun downloadCoreFromLibretroNightly(coreID: CoreID): File =
        withContext(Dispatchers.IO) {
            val mainCoresDirectory = directoriesManager.getCoresDirectory()
            val coresDirectory = File(mainCoresDirectory, NIGHTLY_DIR).apply { mkdirs() }
            val destFile = File(coresDirectory, coreID.libretroFileName)

            if (destFile.exists()) {
                Timber.i("Core ${coreID.coreName} already present, skipping download")
                return@withContext destFile
            }

            // Remove old versioned directories left by the previous LemuroidCores scheme
            runCatching { deleteOutdatedCores(mainCoresDirectory) }

            val zipUrl = buildNightlyZipUrl(coreID)
            Timber.i("Downloading nightly core ${coreID.coreName} from $zipUrl")

            try {
                extractCoreFromZip(zipUrl, coreID, destFile)
                destFile
            } catch (e: Throwable) {
                destFile.safeDelete()
                throw e
            }
        }

    private fun buildNightlyZipUrl(coreID: CoreID): String =
        "$LIBRETRO_NIGHTLY_BASE${coreID.coreName}_libretro_android.so.zip"

    private suspend fun extractCoreFromZip(
        zipUrl: String,
        coreID: CoreID,
        destFile: File,
    ) {
        val response = api.downloadZip(zipUrl)

        if (!response.isSuccessful) {
            val msg = response.errorBody()?.string() ?: "HTTP ${response.code()}"
            Timber.e("Failed to download nightly core ${coreID.coreName}: $msg")
            throw Exception("Download failed for ${coreID.coreName}: $msg")
        }

        val zipInputStream: ZipInputStream =
            response.body() ?: throw Exception("Empty body for ${coreID.coreName}")

        // Entry inside the zip has NO "lib" prefix
        val expectedEntry = "${coreID.coreName}_libretro_android.so"

        zipInputStream.use { zis ->
            var entry = zis.nextEntry
            var found = false
            while (entry != null) {
                if (!entry.isDirectory && entry.name == expectedEntry) {
                    FileOutputStream(destFile).use { out -> zis.copyTo(out) }
                    found = true
                    break
                }
                entry = zis.nextEntry
            }
            if (!found) {
                throw Exception(
                    "Entry '$expectedEntry' not found inside zip for core ${coreID.coreName}",
                )
            }
        }

        Timber.i("Core ${coreID.coreName} saved to ${destFile.absolutePath}")
    }

    /**
     * Deletes every sub-directory that is NOT the nightly folder so that
     * old LemuroidCores-versioned downloads (e.g. "1.17.0/") do not pile up.
     */
    private fun deleteOutdatedCores(mainCoresDirectory: File) {
        mainCoresDirectory.listFiles()
            ?.filter { it.name != NIGHTLY_DIR }
            ?.forEach { it.deleteRecursively() }
    }
}
