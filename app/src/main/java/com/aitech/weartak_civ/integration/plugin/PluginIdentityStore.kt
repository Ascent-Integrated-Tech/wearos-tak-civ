package com.aitech.weartak_civ.integration.plugin

/**
 * Stable host-side identity cache for plugin list items.
 *
 * The host binds and unbinds by package name, but the UI can call into the adapter using the
 * plugin-declared id. This store keeps the stable package record and the latest known declared
 * id together so the adapter can resolve either form without depending on bind state.
 */
internal class PluginIdentityStore {
    private val lock = Any()
    private val recordsByPackageName = LinkedHashMap<String, PluginIdentityRecord>()
    private val packageNameByPluginId = LinkedHashMap<String, String>()

    fun rememberPackage(packageName: String): PluginIdentityRecord = synchronized(lock) {
        recordsByPackageName.getOrPut(packageName) { PluginIdentityRecord(packageName = packageName) }
    }

    fun rememberDeclaredPluginId(
        packageName: String,
        pluginId: String,
    ): PluginIdentityRecord = synchronized(lock) {
        val record = recordsByPackageName.getOrPut(packageName) { PluginIdentityRecord(packageName = packageName) }
        val normalizedId = pluginId.ifBlank { packageName }
        if (record.lastKnownPluginId != normalizedId) {
            packageNameByPluginId.remove(record.lastKnownPluginId)
        }
        record.lastKnownPluginId = normalizedId
        packageNameByPluginId[normalizedId] = packageName
        record
    }

    fun resolvePackageName(identifier: String): String? = synchronized(lock) {
        recordsByPackageName[identifier]?.packageName
            ?: packageNameByPluginId[identifier]
    }

    fun setHidden(packageName: String, hidden: Boolean): Boolean = synchronized(lock) {
        val record = recordsByPackageName[packageName] ?: return@synchronized false
        record.hidden = hidden
        true
    }

    fun removePackage(packageName: String): Boolean = synchronized(lock) {
        val removed = recordsByPackageName.remove(packageName) ?: return@synchronized false
        packageNameByPluginId.remove(removed.lastKnownPluginId)
        true
    }

    fun selectedPackageName(existingSelectedPackageName: String?, visiblePackageNames: Set<String>): String? = synchronized(lock) {
        val candidate = existingSelectedPackageName ?: return@synchronized null
        if (candidate in visiblePackageNames && candidate in recordsByPackageName.keys) candidate else null
    }

    fun visiblePackageNames(discoveredPackageNames: Collection<String>): List<String> = synchronized(lock) {
        discoveredPackageNames.filter { recordsByPackageName[it]?.hidden != true }
    }

    fun lastKnownPluginId(packageName: String): String = synchronized(lock) {
        recordsByPackageName[packageName]?.lastKnownPluginId ?: packageName
    }

    fun isHidden(packageName: String): Boolean = synchronized(lock) {
        recordsByPackageName[packageName]?.hidden == true
    }

    internal data class PluginIdentityRecord(
        val packageName: String,
        var lastKnownPluginId: String = packageName,
        var hidden: Boolean = false,
    )
}
