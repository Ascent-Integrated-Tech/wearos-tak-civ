package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.ContextCompat

internal object PluginIconResolver {
    fun resolve(context: Context, packageName: String): Drawable? {
        val pm = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationIcon(
                    pm.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0L),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationIcon(packageName)
            }
        }.getOrElse {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        }
    }
}
