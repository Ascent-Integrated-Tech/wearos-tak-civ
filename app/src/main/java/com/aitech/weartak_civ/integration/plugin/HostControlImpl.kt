package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.TAKConnectService
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.AlertRequestParcel
import com.weartak.plugin.sdk.model.CepRuleEventParcel
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.model.DeviceStatusParcel
import com.weartak.plugin.sdk.model.HostCapabilitiesParcel
import com.weartak.plugin.sdk.model.MarkerParcel
import com.weartak.plugin.sdk.model.ToastRequestParcel
import com.weartak.plugin.sdk.validation.ControlPlanePayloadValidator
import com.weartak.plugin.sdk.validation.CepRulesValidator
import com.weartak.plugin.sdk.validation.ContractValidationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Host control plane passed to the plugin at init time. Binder calls are synchronous (v1
 * contract); heavy work uses [runBlocking] onto [CepProvider]'s internal dispatcher via
 * [PluginIngressPort].
 */
class HostControlImpl(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val pluginPackage: String,
    private val expectedPluginUid: Int,
    private val mapLayer: HostMapLayerControlImpl = HostMapLayerControlImpl(),
) : IWearTakPluginControl.Stub() {

    private fun verifyCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid != expectedPluginUid) {
            Log.w(TAG, "control call uid mismatch: expected=$expectedPluginUid got=$uid pkg=$pluginPackage")
            return false
        }
        return true
    }

    private fun senderPackages(): Set<String> = ingress.packagesForUid(app, Binder.getCallingUid())

    override fun requestToast(request: ToastRequestParcel?) {
        if (!verifyCaller() || request == null) return
        when (val v = ControlPlanePayloadValidator.validateToast(request)) {
            is ContractValidationResult.Err -> {
                Log.w(TAG, "requestToast validation: ${v.issues}")
                return
            }
            is ContractValidationResult.Ok -> {
                PluginDiagnostics.recordToast(pluginPackage, v.value)
                val duration = if (v.value.duration == ToastRequestParcel.DURATION_LONG) {
                    Toast.LENGTH_LONG
                } else {
                    Toast.LENGTH_SHORT
                }
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(app.applicationContext, v.value.message, duration).show()
                    }.onFailure { Log.e(TAG, "requestToast failed", it) }
                }
            }
        }
    }

    override fun requestCotDispatch(request: CotRequestParcel?) {
        if (!verifyCaller() || request == null) return
        val xml = when (request.payloadFormat.lowercase()) {
            "cot_xml", "xml" -> request.payload.getString("xml")
                ?: request.payload.getString("cotXml")
            else -> request.payload.getString("payload")
        }
        if (xml.isNullOrBlank()) {
            Log.w(TAG, "requestCotDispatch: empty payload format=${request.payloadFormat}")
            return
        }
        runCatching {
            TAKConnectService.sendDataTAKServers(msg = xml, isNotPli = true)
        }.onFailure { Log.e(TAG, "requestCotDispatch failed", it) }
    }

    override fun requestAlert(request: AlertRequestParcel?) {
        if (!verifyCaller() || request == null) return
        when (val v = ControlPlanePayloadValidator.validateAlert(request)) {
            is ContractValidationResult.Err -> {
                Log.w(TAG, "requestAlert validation: ${v.issues}")
                return
            }
            is ContractValidationResult.Ok -> {
                val alert = v.value
                val category = alert.title.ifBlank { "PLUGIN" }
                val description = formatPluginAlertDescription(alert.message, alert.channelId)
                Log.i(
                    TAG,
                    "requestAlert received pkg=$pluginPackage category=$category severity=${alert.severity} " +
                        "channelId=${alert.channelId}",
                )
                runBlocking {
                    runCatching {
                        app.repo.addPhysioAndAutoGenAlertRequest(
                            uid = UUID.randomUUID().toString(),
                            category = category,
                            description = description,
                            priority = alert.severity.coerceAtLeast(0),
                            sendSms = false,
                            timeMillis = System.currentTimeMillis(),
                        )
                        Log.i(TAG, "requestAlert enqueued pkg=$pluginPackage category=$category")
                    }.onFailure { Log.e(TAG, "requestAlert failed", it) }
                }
            }
        }
    }

    override fun requestMarkerPublish(marker: MarkerParcel?) {
        if (!verifyCaller() || marker == null) return
        dispatchMarkerLifecycle(marker, MarkerLifecycle.PUBLISH)
    }

    override fun requestMarkerUpdate(marker: MarkerParcel?) {
        if (!verifyCaller() || marker == null) return
        dispatchMarkerLifecycle(marker, MarkerLifecycle.UPDATE)
    }

    override fun requestMarkerClear(marker: MarkerParcel?) {
        if (!verifyCaller() || marker == null) return
        dispatchMarkerLifecycle(marker, MarkerLifecycle.CLEAR)
    }

    private enum class MarkerLifecycle { PUBLISH, UPDATE, CLEAR }

    private fun dispatchMarkerLifecycle(marker: MarkerParcel, lifecycle: MarkerLifecycle) {
        when (val v = ControlPlanePayloadValidator.validateMarker(marker)) {
            is ContractValidationResult.Err -> {
                Log.w(TAG, "${lifecycle.name} validation: ${v.issues}")
                return
            }
            is ContractValidationResult.Ok -> {
                val parsed = PluginMarkerMapping.fromParcel(v.value)
                val timeMillis = System.currentTimeMillis()
                runBlocking {
                    when (lifecycle) {
                        MarkerLifecycle.PUBLISH -> {
                            val ok = app.repo.requestMarkerDrop(
                                markerTypeRaw = parsed.markerTypeRaw,
                                title = parsed.title,
                                remark = parsed.remark,
                                timeMillis = timeMillis,
                                lat = parsed.lat,
                                lon = parsed.lon,
                                uid = parsed.uid,
                            )
                            if (!ok) {
                                Log.w(
                                    TAG,
                                    "requestMarkerPublish: marker type not parsed typeRaw=${parsed.markerTypeRaw}",
                                )
                            }
                        }
                        MarkerLifecycle.UPDATE -> {
                            val ok = app.repo.requestMarkerUpdate(
                                markerTypeRaw = parsed.markerTypeRaw,
                                title = parsed.title,
                                remark = parsed.remark,
                                timeMillis = timeMillis,
                                uid = parsed.uid,
                                lat = parsed.lat,
                                lon = parsed.lon,
                            )
                            if (!ok) {
                                Log.w(
                                    TAG,
                                    "requestMarkerUpdate: failed uid=${parsed.uid} typeRaw=${parsed.markerTypeRaw}",
                                )
                            }
                        }
                        MarkerLifecycle.CLEAR -> app.repo.requestMarkerClear(parsed.uid)
                    }
                }
            }
        }
    }

    override fun applyCepRuleEvent(event: CepRuleEventParcel?) {
        if (!verifyCaller() || event == null) return
        when (val v = CepRulesValidator.validateEvent(event)) {
            is ContractValidationResult.Err -> {
                Log.w(TAG, "applyCepRuleEvent validation: ${v.issues}")
                return
            }
            is ContractValidationResult.Ok -> {
                val uid = Binder.getCallingUid()
                val pkgs = senderPackages()
                val outcome = ingress.applyCepRuleEventBlocking(pluginPackage, v.value, uid, pkgs)
                Log.i(TAG, "applyCepRuleEvent outcome=$outcome pluginId=$pluginPackage sensorKey=${v.value.sensorKey}")
            }
        }
    }

    override fun getDeviceStatus(): DeviceStatusParcel {
        if (!verifyCaller()) {
            return DeviceStatusParcel(online = false, summary = "denied")
        }
        return runBlocking { buildDeviceStatus() }
    }

    private suspend fun buildDeviceStatus(): DeviceStatusParcel {
        val tak = TAKConnectService.isAnyTakConnected
        val sitx = runCatching {
            app.repo.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED
        }.getOrDefault(false)
        val online = tak || sitx
        val telemetry = Bundle()
        val now = System.currentTimeMillis()
        telemetry.putLong(DeviceStatusContract.KEY_SNAPSHOT_TIME_MS, now)
        telemetry.putBoolean(DeviceStatusContract.KEY_TAK_CONNECTED, tak)
        telemetry.putBoolean(DeviceStatusContract.KEY_SITX_CONNECTED, sitx)
        app.repo.latestBatteryPercentage.value?.let {
            telemetry.putInt(DeviceStatusContract.KEY_BATTERY_PERCENT, it)
        }
        app.repo.latestHeartRate.value?.let {
            telemetry.putInt(DeviceStatusContract.KEY_HEART_RATE_BPM, it)
        }
        runCatching { app.repo.getCallsign.first() }.getOrNull()?.let {
            if (it.isNotBlank()) telemetry.putString(DeviceStatusContract.KEY_CALLSIGN, it)
        }
        val loc = app.repo.snapshotLocation()
        if (loc != null) {
            telemetry.putBoolean(DeviceStatusContract.KEY_LOCATION_AVAILABLE, true)
            telemetry.putDouble(DeviceStatusContract.KEY_LAT, loc.lat)
            telemetry.putDouble(DeviceStatusContract.KEY_LON, loc.lon)
        } else {
            telemetry.putBoolean(DeviceStatusContract.KEY_LOCATION_AVAILABLE, false)
        }
        val summary = when {
            !online -> "disconnected"
            tak && sitx -> "tak,sitx"
            tak -> "tak"
            else -> "sitx"
        }
        return DeviceStatusParcel(
            online = online,
            summary = summary,
            telemetry = telemetry,
        )
    }

    override fun registerCepRules(pluginId: String?, sensorKey: String?, rulesJson: String?) {
        if (!verifyCaller()) return
        val pid = pluginId?.trim().orEmpty()
        val sk = sensorKey?.trim().orEmpty()
        val json = rulesJson.orEmpty()
        if (pid.isEmpty() || sk.isEmpty() || json.isEmpty()) return
        if (pid != pluginPackage) {
            Log.w(TAG, "registerCepRules pluginId mismatch claimed=$pid pkg=$pluginPackage")
            return
        }
        when (val v = CepRulesValidator.validateRegistration(pid, sk, json)) {
            is ContractValidationResult.Err -> {
                Log.w(TAG, "registerCepRules validation: ${v.issues}")
                return
            }
            is ContractValidationResult.Ok -> Unit
        }
        val uid = Binder.getCallingUid()
        val pkgs = senderPackages()
        val outcome = ingress.registerCepRulesBlocking(pid, sk, json, uid, pkgs)
        Log.i(TAG, "registerCepRules outcome=$outcome pluginId=$pid sensorKey=$sk")
    }

    override fun unregisterCepRules(pluginId: String?, sensorKey: String?) {
        if (!verifyCaller()) return
        val pid = pluginId?.trim().orEmpty()
        val sk = sensorKey?.trim().orEmpty()
        if (pid.isEmpty() || sk.isEmpty()) return
        if (pid != pluginPackage) return
        val uid = Binder.getCallingUid()
        val pkgs = senderPackages()
        val outcome = ingress.unregisterCepRulesBlocking(pid, sk, uid, pkgs)
        Log.i(TAG, "unregisterCepRules outcome=$outcome pluginId=$pid sensorKey=$sk")
    }

    override fun isCotConnected(): Boolean {
        if (!verifyCaller()) return false
        val tak = TAKConnectService.isAnyTakConnected
        val sitx = runCatching {
            app.repo.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED
        }.getOrDefault(false)
        return tak || sitx
    }

    override fun getHostCapabilities(): HostCapabilitiesParcel {
        if (!verifyCaller()) {
            return HostCapabilitiesParcel()
        }
        return HostCapabilitiesParcel(
            cotDispatch = true,
            alerts = true,
            toasts = true,
            markers = true,
            cepRules = true,
            cepRuleStreaming = true,
            mapLayers = mapLayer.isMapLayerSupported,
            sharedMemory = false,
        )
    }

    override fun getMapLayerControl() = mapLayer

    companion object {
        private const val TAG = "CEP-HostControl"

        internal fun formatPluginAlertDescription(message: String, channelId: String?): String {
            val ch = channelId?.trim().orEmpty()
            return if (ch.isEmpty()) message else "[channel:$ch] $message"
        }
    }
}
