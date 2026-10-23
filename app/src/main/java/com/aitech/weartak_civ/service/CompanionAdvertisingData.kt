package com.aitech.weartak_civ.service

import android.bluetooth.le.AdvertiseData
import android.os.ParcelUuid
import java.util.UUID

internal object CompanionAdvertisingData {
    const val VERSION: Byte = 1
    // 31 bytes minus AD length/type (2), a worst-case 128-bit UUID (16), and version (1).
    const val MAX_CALLSIGN_BYTES = 12

    fun encodeCallsign(callsign: String): ByteArray {
        var end = 0
        var bytes = 0
        while (end < callsign.length) {
            val next = end + Character.charCount(callsign.codePointAt(end))
            val size = callsign.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_CALLSIGN_BYTES) break
            bytes += size
            end = next
        }
        return byteArrayOf(VERSION) + callsign.substring(0, end).toByteArray(Charsets.UTF_8)
    }

    fun primary(serviceUuid: UUID): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(serviceUuid))
        .build()

    fun scanResponse(serviceUuid: UUID, callsign: ByteArray): AdvertiseData = AdvertiseData.Builder()
        .addServiceData(ParcelUuid(serviceUuid), callsign)
        .build()
}
