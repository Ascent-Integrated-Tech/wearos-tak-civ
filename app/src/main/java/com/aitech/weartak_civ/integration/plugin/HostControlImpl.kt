package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.TAKConnectService
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.AlertRequestParcel
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.model.DeviceStatusParcel
import com.weartak.plugin.sdk.model.HostCapabilitiesParcel
import com.weartak.plugin.sdk.model.MarkerParcel
import com.weartak.plugin.sdk.validation.CepRulesValidator
import com.weartak.plugin.sdk.validation.ContractValidationResult
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
        runBlocking {
            runCatching {
                app.repo.addPhysioAndAutoGenAlertRequest(
                    uid = UUID.randomUUID().toString(),
                    category = request.title.ifBlank { "PLUGIN" },
                    description = request.message,
                    priority = request.severity.coerceAtLeast(0),
                    sendSms = false,
                    timeMillis = System.currentTimeMillis(),
                )
            }.onFailure { Log.e(TAG, "requestAlert failed", it) }
        }
    }

    override fun requestMarkerPublish(marker: MarkerParcel?) {
        if (!verifyCaller() || marker == null) return
        val typeRaw = marker.attributes.getString("markerType") ?: "UNKNOWN"
        val title = marker.title?.ifBlank { marker.markerId } ?: marker.markerId
        val remark = marker.attributes.getString("remark") ?: ""
        runBlocking {
            val ok = app.repo.requestMarkerDrop(
                markerTypeRaw = typeRaw,
                title = title,
                remark = remark,
                timeMillis = System.currentTimeMillis(),
                lat = if (marker.attributes.containsKey("lat")) marker.attributes.getDouble("lat") else null,
                lon = if (marker.attributes.containsKey("lon")) marker.attributes.getDouble("lon") else null,
                uid = marker.markerId,
            )
            if (!ok) Log.w(TAG, "requestMarkerPublish: marker type not parsed typeRaw=$typeRaw")
        }
    }

    override fun getDeviceStatus(): DeviceStatusParcel {
        if (!verifyCaller()) {
            return DeviceStatusParcel(online = false, summary = "denied")
        }
        val tak = TAKConnectService.isAnyTakConnected
        val sitx = runCatching {
            app.repo.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED
        }.getOrDefault(false)
        val online = tak || sitx
        return DeviceStatusParcel(
            online = online,
            summary = if (online) "connected" else "disconnected",
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
            markers = true,
            cepRules = true,
            mapLayers = false,
            sharedMemory = false,
        )
    }

    override fun getMapLayerControl() = mapLayer

    companion object {
        private const val TAG = "CEP-HostControl"
    }
}
