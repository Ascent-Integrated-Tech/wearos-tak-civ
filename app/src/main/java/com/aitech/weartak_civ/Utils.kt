package com.aitech.weartak_civ

import android.os.Build
import android.util.Log
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object Utils {
    private const val TAG = "WearTAK-Utils"
    private var logFileFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val DECIMAL_DEGREES_PER_METER = 3.631033E-06 //Approximate decimal degrees per meter at the equator
    const val EARTH_RADIUS = 6378137.0  // in meters

    fun isValidJson(jsonString: String) : Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return try {
            Gson().fromJson(jsonString, Any::class.java)
            true
        } catch (e: JsonSyntaxException) {
            false
        }
    }

    fun getValueFromJson(jsonString: String, key: String): String? {
        return try {
            val gson = Gson()
            val jsonObject: JsonObject = gson.fromJson(jsonString, JsonObject::class.java)
            jsonObject.get(key)?.asString
        } catch (e: Exception) {
            null
        }
    }

    fun isStringEmptyJson(jsonString: String): Boolean {
        if (jsonString.isNullOrEmpty()) {
            return true
        }

        return try {
            Gson().fromJson(jsonString, Any::class.java)
            jsonString.trim() == "{}" || jsonString.trim() == "[]"
        } catch (e: JsonParseException) {
            false
        }
    }

    fun writeToLogFileLocation(logFile: File?, data: String) {
        if (null == logFile) {
            return
        }
        try {
            val writer = FileWriter(logFile, true) // Append to existing file
            writer.write("${LocalDateTime.now().format(logFileFormatter)}: $data\n")
            writer.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log to file: ${e.message}", e)
        }
    }

    fun jitteredLatLon(lat: Double, lon: Double, maxJitterM: Double): Pair<Double, Double> {
        val maxJitterUnsignedM = abs(maxJitterM)
        // Generate random offsets in [-maxJitterUnsignedM, maxJitterUnsignedM] meters
        val dx = (Math.random() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // East-West
        val dy = (Math.random() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // North-South
        Log.d(TAG, "jitteredLatLon: dlatM($dy), dLonM($dx): dist(${(sqrt(dx*dx + dy*dy))})")

        // Convert to angular distance in radians
        val dLat = dy / EARTH_RADIUS
        val dLon = dx / (EARTH_RADIUS * cos(Math.toRadians(lat)))

        // Apply to original coordinates
        val newLat = lat + Math.toDegrees(dLat)
        val newLon = lon + Math.toDegrees(dLon)

        return Pair(newLat, newLon)
    }

    fun jitteredLatLon1(lat: Double, lon: Double, minJitterM: Double, maxJitterM: Double): Pair<Double, Double> {
        val jitterDist = Random.nextDouble(minJitterM, maxJitterM)

        val latitudeJitter = jitterDist * DECIMAL_DEGREES_PER_METER
        val longitudeJitter = jitterDist * DECIMAL_DEGREES_PER_METER

        return Pair(lat + latitudeJitter, lon + longitudeJitter)

    }

    /* Ditto Track Document uses this legacy cot field. It is
     * some sort of convoluted, inefficient JSON string and is
     * viciously unreadable, but required for meshing PLI in tak.
     * This function alleviates the pain.
    */
    fun cotNode(
        name: String,
        attrs: Map<String, Any?> = emptyMap(),
        children: Map<String, Any?> = emptyMap(),
        indexValue: Any? = null
    ): Map<String, Any?> = mapOf(
        "a" to attrs,
        "d" to children,
        "i" to indexValue,
        "n" to name
    )

    // 1) Ditto‐only version
    fun buildCotDetailDitto(
        callsign: String,
        roleValue: String,
        teamValue: String,
        uid: String,
        battery: Int,
        // takv attributes
        takvOs: String,
        takvVersion: String,
        takvDevice: String,
        takvPlatform: String,
        // precisionlocation (defaults to GPS)
        altsrc: String = "GPS",
        geopointsrc: String = "GPS",
        // ditto attributes
        dittoA: String,
        dittoIp: String,
        dittoVersion: String,
        dittoDeviceName: String,
        // contact endpoint
        endpoint: String = "*:-1:stcp"
    ): String {
        return """
        <detail>
            <takv os='$takvOs' version='$takvVersion' device='$takvDevice' platform='$takvPlatform'/>
            <contact endpoint='$endpoint' callsign='$callsign'/>
            <uid Droid='$uid'/>
            <precisionlocation altsrc='$altsrc' geopointsrc='$geopointsrc'/>
            <__group role='$roleValue' name='$teamValue'/>
            <status battery='$battery'/>
            <ditto a='$dittoA' ip='$dittoIp' version='$dittoVersion' deviceName='$dittoDeviceName'/>
        </detail>
    """.trimIndent()
    }

    // 1
    fun buildCotDetail(
        // existing parameters
        callsign: String,
        roleValue: String,
        teamValue: String,
        uid: String,
        battery: Int,
        // takv-related
        takvOs: String,
        takvVersion: String,
        takvDevice: String,
        takvPlatform: String,
        // precisionlocation (defaults to GPS)
        altsrc: String = "GPS",
        geopointsrc: String = "GPS",
        // ditto-related
        dittoA: String,
        dittoIp: String,
        dittoVersion: String,
        dittoDeviceName: String,
        // contact endpoint (default if you still want it)
        endpoint: String = "*:-1:stcp"
    ): String {
        return """
        <detail>
            <takv 
                os='$takvOs' 
                version='$takvVersion' 
                device='$takvDevice' 
                platform='$takvPlatform'
            />
            <contact 
                endpoint='$endpoint' 
                callsign='$callsign'
            />
            <uid Droid='$uid'/>
            <precisionlocation 
                altsrc='$altsrc' 
                geopointsrc='$geopointsrc'
            />
            <__group 
                role='$roleValue' 
                name='$teamValue'
            />
            <status battery='$battery'/>
            <ditto 
                a='$dittoA' 
                ip='$dittoIp' 
                version='$dittoVersion' 
                deviceName='$dittoDeviceName'
            />
        </detail>
    """.trimIndent()
    }
    fun buildCotDetailMerged(
        // — your original fields —
        heartRate: Int,
        battery:    Int,
        callsign:   String,
        roleValue:  String,
        teamValue:  String,
        uid:        String,
        bearingActual: Float,
        speedActual:   Float,

        // — ATAK version/device info (matches your Ditto‐only sample) —
        takvOs:      String = "WEAROS",
        takvVersion: String = "WearTAK-${Build.VERSION.RELEASE}",
        takvDevice:  String,
        takvPlatform:String = "ATAK-CIV",

        // — precisionlocation defaults to GPS —
        altsrc:      String = "GPS",
        geopointsrc: String = "GPS",

        // — ditto payload (matches your Ditto‐only sample) —
        dittoA:          String,
        dittoIp:         String = "0.0.0.0",
        dittoVersion:    String,
        dittoDeviceName: String,

        // — contact defaults —
        endpoint:   String = "*:-1:stcp",
        phone:      String = "11234567890"
    ): String = "<detail><takv os='$takvOs' version='$takvVersion' device='$takvDevice' platform='$takvPlatform'/><contact endpoint='$endpoint' callsign='$callsign'/><uid Droid='$uid'/><precisionlocation altsrc='$altsrc' geopointsrc='$geopointsrc'/><__group role='$roleValue' name='$teamValue'/><status battery='$battery'/><remarks>HR: $heartRate bpm; Batt: $battery%</remarks><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/><ditto a='$dittoA' ip='$dittoIp' version='$dittoVersion' deviceName='$dittoDeviceName'/></detail>"

    fun buildCotDetailOld(
        heartRate: Int,
        battery: Int,
        callsign: String,
        roleValue: String,
        teamValue: String,
        uid: String,
        bearingActual: Float,
        speedActual: Float,
        endpoint: String = "*:-1:stcp",
        phone: String = "11234567890"
    ): String {
        return """<detail><remarks>HR: $heartRate bpm; Batt: $battery%</remarks><status battery='$battery'/><contact endpoint='$endpoint' phone='$phone' callsign='$callsign'/><__group role='$roleValue' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail>""".trimIndent()}

    /** Minimal data models for chat rooms & messages. */
    data class ChatMessage(
        val message: String,
        val uid: String,
        val callsign: String,
        val timeReceived: Long
    )

    data class ChatRoom(
        val uid: String,
        val title: String,
        val messageList: List<ChatMessage>,
        var newMessagesPresent: Boolean,
        val timeLastOpened: Long,
        var timeLastClosed: Long
    )

    object CotUtils {
        /** Quick screen for chat‐related COTs. */
        fun isChatMessage(raw: String): Boolean =
            raw.contains("<__chat") && raw.contains("<remarks")

        /** Is this message addressed to me or to “All Chat Rooms”? */
        fun isChatForMe(raw: String, myUid: String): Boolean {
            return raw.contains("""to='$myUid'""")
                    || raw.contains("""to="$myUid"""")
                    || raw.contains("""to='All Chat Rooms'""")
                    || raw.contains("""to="All Chat Rooms"""")
        }

        /** DOM‐plus‐regex hybrid parser for extracting ChatRooms. */
        fun parseChatRooms(raw: String, myUid: String): List<ChatRoom> {
            val rooms = mutableListOf<ChatRoom>()

            // 1) Try a secure DOM parse
            val doc = try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isNamespaceAware = true
                    isValidating = false
                    isXIncludeAware = false
                }
                factory.newDocumentBuilder()
                    .parse(InputSource(StringReader(raw)))
            } catch (_: Exception) {
                null
            }

            if (doc != null) {
                val chatElems   = doc.getElementsByTagName("__chat")
                val remarkElems = doc.getElementsByTagName("remarks")
                for (i in 0 until chatElems.length) {
                    val c = chatElems.item(i) as? Element ?: continue
                    // extract sender device UID
                    val grpEl        = c.getElementsByTagName("chatgrp").item(0) as? Element
                    val senderUid    = grpEl?.getAttribute("uid0") ?: continue
                    val senderCallsign = c.getAttribute("senderCallsign")

                    for (j in 0 until remarkElems.length) {
                        val r     = remarkElems.item(j) as? Element ?: continue
                        val toUid = r.getAttribute("to")
                        val text  = r.textContent.trim()
                        if (toUid == myUid || toUid == "All Chat Rooms") {
                            rooms += ChatRoom(
                                uid               = senderUid,
                                title             = senderCallsign,
                                messageList       = listOf(ChatMessage(text, senderUid, senderCallsign, System.currentTimeMillis())),
                                newMessagesPresent= true,
                                timeLastOpened    = 0L,
                                timeLastClosed    = 0L
                            )
                        }
                    }
                }
            } else {
                // 2) Regex fallback: grab senderUid and callsign, then remarks
                val grpRx    = """<chatgrp[^>]*uid0=['"]([^'"]+)['"]""".toRegex()
                val callRx   = """<__chat[^>]*senderCallsign=['"]([^'"]+)['"]""".toRegex()
                val remarkRx = """<remarks[^>]*to=['"]([^'"]+)['"][^>]*>(.*?)</remarks>""".toRegex(RegexOption.DOT_MATCHES_ALL)

                val grpMatch  = grpRx.find(raw)
                val callMatch = callRx.find(raw)
                val rMatches  = remarkRx.findAll(raw)

                if (grpMatch != null && callMatch != null) {
                    val senderUid      = grpMatch.groupValues[1]
                    val senderCallsign = callMatch.groupValues[1]

                    for (m in rMatches) {
                        val toUid = m.groupValues[1]
                        val text  = m.groupValues[2].trim()
                        if (toUid == myUid || toUid == "All Chat Rooms") {
                            rooms += ChatRoom(
                                uid               = senderUid,
                                title             = senderCallsign,
                                messageList       = listOf(ChatMessage(text, senderUid, senderCallsign, System.currentTimeMillis())),
                                newMessagesPresent= true,
                                timeLastOpened    = 0L,
                                timeLastClosed    = 0L
                            )
                        }
                    }
                }
            }

            return rooms
        }
    }
}
