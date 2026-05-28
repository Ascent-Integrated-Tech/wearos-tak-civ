/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

private data class SupportedWearTakPlugin(
    val packageName: String,
    val fallbackName: String,
)

data class InstalledWearTakPlugin(
    val packageName: String,
    val displayName: String,
    val icon: Drawable?,
    val launcherComponent: ComponentName?,
    val launchIntent: Intent?,
) {
    val isLaunchable: Boolean
        get() = launcherComponent != null || launchIntent != null
}

object WearTakPluginCatalog {
    private val supportedPlugins = listOf(
        SupportedWearTakPlugin(
            packageName = "com.toughstump.atoswearable",
            fallbackName = "ATOS",
        ),
    )

    fun discoverInstalledPlugins(context: Context): List<InstalledWearTakPlugin> {
        val packageManager = context.packageManager

        return supportedPlugins.mapNotNull { supportedPlugin ->
            val applicationInfo = try {
                packageManager.getApplicationInfo(supportedPlugin.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null

            val launcherComponent = resolveLauncherComponent(
                packageManager = packageManager,
                packageName = supportedPlugin.packageName,
            )
            val launchIntent = launcherComponent?.let {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = it
                }
            }

            InstalledWearTakPlugin(
                packageName = supportedPlugin.packageName,
                displayName = packageManager.getApplicationLabel(applicationInfo)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: supportedPlugin.fallbackName,
                icon = runCatching { applicationInfo.loadIcon(packageManager) }.getOrNull(),
                launcherComponent = launcherComponent,
                launchIntent = launchIntent,
            )
        }
    }

    private fun resolveLauncherComponent(
        packageManager: PackageManager,
        packageName: String,
    ): ComponentName? {
        val launcherQueryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }

        val resolveInfo = packageManager.resolveActivity(
            launcherQueryIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: packageManager.queryIntentActivities(launcherQueryIntent, 0).firstOrNull()
            ?: return null

        return ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
    }
}
