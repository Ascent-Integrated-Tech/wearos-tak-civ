package com.aitech.weartak_civ.cot

import android.os.Build

object CotDetailBuilder {
    fun dittoTrack(
        heartRate: Int,
        battery: Int,
        callsign: String,
        role: String,
        team: String,
        uid: String,
        course: Float,
        speed: Float,
        takvOs: String = "WEAROS",
        takvVersion: String = "WearTAK-${Build.VERSION.RELEASE}",
        takvDevice: String,
        takvPlatform: String = "ATAK-CIV",
        altitudeSource: String = "GPS",
        geopointSource: String = "GPS",
        dittoA: String,
        dittoIp: String = "0.0.0.0",
        dittoVersion: String,
        dittoDeviceName: String,
        endpoint: String = "*:-1:stcp",
    ): String = "<detail><takv os='$takvOs' version='$takvVersion' device='$takvDevice' platform='$takvPlatform'/><contact endpoint='$endpoint' callsign='$callsign'/><uid Droid='$uid'/><precisionlocation altsrc='$altitudeSource' geopointsrc='$geopointSource'/><__group role='$role' name='$team'/><status battery='$battery'/><remarks>HR: $heartRate bpm; Batt: $battery%</remarks><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$course' speed='$speed'/><ditto a='$dittoA' ip='$dittoIp' version='$dittoVersion' deviceName='$dittoDeviceName'/></detail>"
}
