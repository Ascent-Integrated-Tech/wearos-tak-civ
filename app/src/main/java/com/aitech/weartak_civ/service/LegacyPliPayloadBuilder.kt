package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.data.DataRepository
import kotlin.math.roundToInt

object LegacyPliPayloadBuilder {
    data class Options(
        val callsign: String,
        val role: String,
        val team: String,
        val includeActivityMetrics: Boolean,
        val buildEsri: Boolean,
        val buildTabletCommand: Boolean,
    )

    data class Payloads(
        val esriGeoJson: String?,
        val tabletCommandJson: String?,
    )

    fun build(snapshot: DataRepository.PliSnapshot, options: Options): Payloads {
        val time = snapshot.time
        val physio = snapshot.physioData.data
        val location = snapshot.loc
        val system = snapshot.systemData
        val metrics = physio.dailyMetrics
        val start = time.startCot
        val stale = time.staleCot
        val lat = location?.lat ?: 0.0
        val lon = location?.lon ?: 0.0
        val hae = location?.hae ?: 0.0
        val ce = location?.ce?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val le = location?.le?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val course = location?.course?.toInt() ?: 0
        val speed = location?.speed?.toInt() ?: 0
        val uid = system.deviceId
        val heartRate = rectifiedInt(physio.heartRateBpm)
        val skinTemperature = rectifiedNumber(physio.skinTempF, "%.1f", negIsInvalid = false, zeroIsInvalid = false)
        val exertion = rectifiedNumber(physio.exertion, "%.2f", zeroIsInvalid = false)
        val battery = system.batteryPercent
        val steps = metrics?.steps ?: 0L
        val floors = metrics?.floors ?: 0.0
        val isWorn = physio.heartRateBpm != -1

        val esri = if (options.buildEsri) {
            val activityFields = if (options.includeActivityMetrics) {
                "\"steps\": $steps, \"floors\": ${floors.roundToInt()}"
            } else {
                "\"steps\": -1, \"floors\": -1"
            }
            """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$start", "staletimeutc": "$stale", "callsign": "${options.callsign}", "role": "${options.role}", "color": "${options.team}", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ce, "vertaccuracy": $le, "heading": $course, "speed": $speed, "batt": $battery, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": $heartRate, "hribi": -1, "hrv": -1, "skintemp": $skinTemperature, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", $activityFields, "exertion": $exertion, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": $battery } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()
        } else {
            null
        }

        val tabletCommand = if (options.buildTabletCommand) {
            val activityFields = if (options.includeActivityMetrics) {
                "\"steps\":$steps,\"floors\":${floors.roundToInt()}"
            } else {
                "\"steps\":-1,\"floors\":-1"
            }
            """
                {"id":"$uid","timeutc":"$start","staletimeutc":"$stale","callsign":"${options.callsign}","role":"${options.role}","color":"${options.team}","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ce,"vertaccuracy":$le,"heading":$course,"speed":$speed,"batt":$battery,"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":$battery,"hribi":-1,"hrv":-1,"skintemp":$skinTemperature,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"",$activityFields,"exertion":$exertion,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":$battery}],"alerts":[],"resolvedalerts":[]}
            """.trimIndent()
        } else {
            null
        }

        return Payloads(esri, tabletCommand)
    }

    private fun rectifiedInt(value: Int?): String =
        if (value == null || value < 0) "N/A" else value.toString()

    private fun rectifiedNumber(
        value: Number?,
        format: String,
        invalidString: String = "N/A",
        negIsInvalid: Boolean = true,
        zeroIsInvalid: Boolean = true,
    ): String {
        if (value == null) return invalidString
        val number = value.toDouble()
        if (negIsInvalid && number < 0.0 || zeroIsInvalid && number == 0.0) return invalidString
        val formatted = String.format(format, number)
        return when {
            format.endsWith("1f") && formatted.endsWith(".0") -> formatted.removeSuffix(".0")
            format.endsWith("2f") && formatted.endsWith(".00") -> formatted.removeSuffix(".00")
            else -> formatted
        }
    }
}
