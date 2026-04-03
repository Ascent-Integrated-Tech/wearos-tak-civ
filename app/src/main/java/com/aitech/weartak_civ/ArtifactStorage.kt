package com.aitech.weartak_civ

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

object ArtifactStorage {
    private const val TAG = "WearTAK-ArtifactStorage"

    @Suppress("DEPRECATION")
    private fun appArtifactRoot(context: Context): File {
        val sharedMediaRoot = context.externalMediaDirs.firstOrNull()?.let { mediaRoot ->
            if (mediaRoot.exists() || mediaRoot.mkdirs()) {
                mediaRoot
            } else {
                null
            }
        }
        if (sharedMediaRoot != null) {
            return sharedMediaRoot
        }

        val externalRoot = context.getExternalFilesDir(null)
        if (externalRoot != null) {
            externalRoot.mkdirs()
            Log.w(TAG, "External media dir unavailable; falling back to external app files dir")
            return externalRoot
        }

        Log.w(TAG, "External storage unavailable; falling back to internal files dir")
        return context.filesDir
    }

    fun gunshotDebugRoot(context: Context): File =
        File(appArtifactRoot(context), "gunshot_debug").apply { mkdirs() }

    fun fieldAudioRoot(context: Context): File {
        val sdcard = Environment.getExternalStorageDirectory()
        val fieldRoot = if (sdcard != null) {
            File(sdcard, "Documents/field_audio")
        } else {
            Log.w(TAG, "External storage unavailable; defaulting to app files for field audio")
            File(context.filesDir, "field_audio")
        }

        val created = fieldRoot.exists() || fieldRoot.mkdirs()
        Log.d(TAG, "fieldAudioRoot=${fieldRoot.absolutePath}, exists=${fieldRoot.exists()}, created=$created, canWrite=${fieldRoot.canWrite()}, usableSpace=${fieldRoot.usableSpace}")

        return fieldRoot
    }
}
