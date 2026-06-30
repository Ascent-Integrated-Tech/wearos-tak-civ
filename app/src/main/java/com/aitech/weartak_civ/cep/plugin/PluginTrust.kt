package com.aitech.weartak_civ.cep.plugin

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * v1 trust gate: the broadcaster's UID must own the claimed [pluginId] (sender-package
 * binding), and the SHA-256 of the package's signing cert must match the first-handshake
 * pinned value on subsequent submissions (cert pinning).
 *
 * Per WEAR-508/509 design §477 the trust contract is "this UID claims this pluginId — does
 * the pinned cert match the package that owns this UID?" — NOT "does the pinned cert match
 * whatever package happens to have this name?". The receiver layer is responsible for
 * resolving the caller's UID and package set (via `Binder.getCallingUid()` +
 * `PackageManager.getPackagesForUid`) BEFORE invoking [enforce].
 */
class PluginTrust(
    private val context: Context,
    private val repo: DataRepository,
) {
    private val certMutex = Mutex()

    /**
     * Bind the claimed [pluginId] to the resolved sender identity, then run cert pinning.
     *
     * @param pluginId the pluginId claimed by the broadcast.
     * @param senderUid uid of the calling process, resolved by the receiver. Used for
     *   diagnostic context; the actual binding is checked against [senderPackages].
     * @param senderPackages the packages owned by the caller's UID. If [pluginId] is not in
     *   this set, the broadcast is rejected as a sender/package mismatch — closing the
     *   first-handshake spoofability gap called out in the 2026-05-11 review.
     *
     * Throws [ParserException] with [ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH] or
     * [ReasonCodes.PLUGIN_CERT_CHANGED] on failure.
     */
    suspend fun enforce(pluginId: String, senderUid: Int, senderPackages: Set<String>) {
        // Sender-package binding: the calling UID must actually own the claimed pluginId.
        if (pluginId !in senderPackages) {
            throw ParserException(
                ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
                "sender uid=$senderUid pkgs=${senderPackages.joinToString()} does not own pluginId=$pluginId"
            )
        }

        certMutex.withLock {
            val certMap = repo.getCepPluginCertLogOnce()
            val digest = certSha256(pluginId)
            val pinned = certMap[pluginId]
            if (pinned == null) {
                repo.storeCepPluginCertLog(certMap + (pluginId to digest))
            } else if (pinned != digest) {
                throw ParserException(ReasonCodes.PLUGIN_CERT_CHANGED, "plugin cert changed for $pluginId")
            }
        }
    }

    private fun certSha256(packageName: String): String {
        val pm = context.packageManager
        val pkg: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signatures = pkg.signingInfo?.apkContentsSigners
            ?: throw ParserException(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH, "package not signed: $packageName")
        val first = signatures.firstOrNull()?.toByteArray()
            ?: throw ParserException(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH, "no cert for package $packageName")
        val dig = MessageDigest.getInstance("SHA-256").digest(first)
        return dig.joinToString("") { "%02x".format(it) }
    }

}
