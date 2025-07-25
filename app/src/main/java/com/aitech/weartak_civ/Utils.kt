package com.aitech.weartak_civ

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.CotDispatchService.Companion.currReportingIntervalInSecs
import com.aitech.weartak_civ.service.TAKConnectService
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
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLSocket
import com.aitech.weartak_civ.Detail
import com.aitech.weartak_civ.Contact
import com.aitech.weartak_civ.Group
import com.aitech.weartak_civ.Track
import com.aitech.weartak_civ.CotEvent
import com.aitech.weartak_civ.TakMessage
import com.aitech.weartak_civ.TakControl


object Utils {
    private const val TAG = "WearTAK-Utils"
    private var logFileFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val DECIMAL_DEGREES_PER_METER = 3.631033E-06 //Approximate decimal degrees per meter at the equator
    const val EARTH_RADIUS = 6378137.0  // in meters
    val secureRandom: SecureRandom = SecureRandom()

    fun <T> listEqualsIgnoreOrder(list1: List<T>, list2: List<T>): Boolean {
        return list1.size == list2.size && list1.toSet() == list2.toSet()
	}

    /**
     * Starts this Service in the foreground, creating a notification channel
     * named after the service class. Defaults can be overridden.
     *
     * @param notificationId  the ID for startForeground(...)
     * @param iconRes         small icon resource (default = R.drawable.splash_icon)
     * @param contentText     text shown under the icon (default = "<ServiceName> running")
     * @param channelId       ID for the channel (default = "<ServiceName>_channel")
     * @param channelName     human-readable channel name (default = "<ServiceName> Background Channel")
     * @param importance      channel importance (default = IMPORTANCE_LOW)
     */
    @SuppressLint("ObsoleteSdkInt", "ServiceCast")
    fun Service.startForegroundServiceCompat(
        notificationId: Int,
        @DrawableRes iconRes: Int = R.drawable.splash_icon,
        contentText: String = "${this.javaClass.simpleName} running",
        channelId: String = "${this.javaClass.simpleName}_channel",
        channelName: String = "${this.javaClass.simpleName} Background Channel",
        importance: Int
    ) {
        val tag = this.javaClass.simpleName
        Log.d(tag, "Starting $tag as ForegroundService")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, importance).apply {
                lightColor = Color.GRAY
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }

        // Build the notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(iconRes)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // Finally, start as foreground
        try {
            startForeground(notificationId, notification)
        } catch (e: NullPointerException) {
            Log.e(tag, "Failed to set $tag as ForegroundService", e)
        }
    }

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
        val dx = (secureRandom.nextDouble() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // East-West
        val dy = (secureRandom.nextDouble() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // North-South
        Log.d(TAG, "jitteredLatLon: dlatM($dy), dLonM($dx): dist(${(sqrt(dx*dx + dy*dy))})")

        // Convert to angular distance in radians
        val dLat = dy / EARTH_RADIUS
        val dLon = dx / (EARTH_RADIUS * cos(Math.toRadians(lat)))

        // Apply to original coordinates
        val newLat = lat + Math.toDegrees(dLat)
        val newLon = lon + Math.toDegrees(dLon)

        return Pair(newLat, newLon)
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

        private const val STREAM_MAGIC: Byte = 0xBF.toByte()

        /**
         *  Builds and serializes a Tak Protocol Payload for
         *  COT Events using google protobuf. For more info, see:
         *
         *  src/main/proto/com/aitech/weartak_civ/protocol.txt
         *
         */
        @Throws(Exception::class)
        fun buildTakProtoPayload(
            location: android.location.Location?,
            physioData: CotDispatchService.PhysioData,
            takServerDetail: TAKConnectService.TakServerDetail
        ): ByteArray {
            val pVer       = takServerDetail.takProtocol?.protobufVersion ?: 1
            val pUid       = takServerDetail.takProtocol?.protobufUid ?: ""

            val now = System.currentTimeMillis()
            val staleSec  = (3L * currReportingIntervalInSecs * 1_000).toLong()
            val staleTime = (now) + staleSec

            val uid       = MainActivity.device_uid
            val lat       = location?.latitude  ?: 0.0
            val lon       = location?.longitude ?: 0.0
            val hae       = (location?.altitude ?: 0.0)
            val ce        = location?.accuracy?.takeIf { !it.isNaN() }?.toInt() ?: 9999
            val le        = location?.verticalAccuracyMeters?.takeIf { !it.isNaN() }?.toInt() ?: 9999
            val course    = location?.bearing?.toInt() ?: 0
            val speed     = location?.speed?.toInt()   ?: 0

            val hr        = physioData.heartRate
            val st        = physioData.skinTemp.toInt()
            val bat       = physioData.battery

            val detail = Detail.newBuilder()
                // embed any raw XML or remarks here:
                .setXmlDetail("H:${hr};T:${st};B:${bat}")
                .setContact(Contact.newBuilder()
                    .setCallsign(callsign)
                    .build()
                )
                .setGroup(Group.newBuilder()
                    .setName(teamValue)
                    .setRole(roleValue)
                    .build()
                )
                .setTrack(Track.newBuilder()
                    .setCourse(course.toDouble())
                    .setSpeed(speed.toDouble())
                    .build()
                )
                .build()

            // 3) Build the CotEvent payload
            val cotEvent = CotEvent.newBuilder()
                .setType("a-f-G-U-C")
                .setUid(uid)
                .setSendTime(now)
                .setStartTime(now)
                .setStaleTime(staleTime)
                .setHow("m-g")
                .setLat(lat)
                .setLon(lon)
                .setHae(hae)
                .setCe(ce.toDouble())
                .setLe(le.toDouble())
                .setDetail(detail)
                .build()

            // 4) Optional: include a minimal TakControl (defaults assume v1)
            val control = TakControl.newBuilder()
                .setMinProtoVersion(pVer)
                .setMaxProtoVersion(pVer)
                .setContactUid(pUid)
                .build()

            // 5) Wrap in the top-level TakMessage
            val takMsgBytes = TakMessage.newBuilder()
                .setTakControl(control)
                .setCotEvent(cotEvent)
                .build()
                .toByteArray()

            // 6) Prepend the streaming header: 0xBF + varint(payload length)
            val header = ByteArrayOutputStream().apply {
                write(STREAM_MAGIC.toInt())
                write(encodeVarint(takMsgBytes.size))
            }.toByteArray()

            return ByteBuffer
                .allocate(header.size + takMsgBytes.size)
                .put(header)
                .put(takMsgBytes)
                .array()
        }

        private fun encodeVarint(value: Int): ByteArray {
            var v = value
            val out = ByteArrayOutputStream()
            while (true) {
                var byteVal = v and 0x7F
                v = v ushr 7
                if (v != 0) byteVal = byteVal or 0x80
                out.write(byteVal)
                if (v == 0) break
            }
            return out.toByteArray()
        }

        /* ----  TAK-Protocol (XML) Parsing helpers  ---- */
        private val uidRx = """<event[^>]*\buid=['"]([^'"]+)['"]""".toRegex()
        private val supportRx = """<TakProtocolSupport[^>]*\bversion=['"](\d+)['"]""".toRegex()
        private val requestRx = """<TakRequest[^>]*\bversion=['"](\d+)['"]""".toRegex()
        private val responseRx = """<TakResponse[^>]*\bstatus=['"]([^'"]+)['"]""".toRegex()

        fun isHandshakeCot(raw: String): Boolean =
            raw.contains("type=\"t-x-takp-")

        fun isSupportCot(raw: String): Boolean =
            raw.contains("type=\"t-x-takp-v\"") && supportRx.containsMatchIn(raw)

        fun isResponseCot(raw: String, uid: String?): Boolean {
            if (uid.isNullOrEmpty()) return false
            return raw.contains("type=\"t-x-takp-r\"") && raw.contains("uid=\"$uid\"")
        }

        fun extractProtoUid(raw: String): String =
            uidRx.find(raw)?.groupValues?.getOrNull(1) ?: ""

        fun extractProtoVers(raw: String): Int? =
            supportRx.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: requestRx.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

        fun protoEnabled(raw: String): Boolean =
            responseRx.find(raw)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() ?: false

        private val INSTANT_FMT = DateTimeFormatter.ISO_INSTANT

        fun eventTimeNow(): String = INSTANT_FMT.format(Instant.now())

        fun eventTimeStale(factor: Long = 3): String =
            INSTANT_FMT.format(Instant.now().plusSeconds(currReportingIntervalInSecs * factor))

        fun eventTimeStamps(factor: Long = 3): Pair<String, String> =
            eventTimeNow().let { ts -> ts to eventTimeStale(factor) }

        /* Build <t-x-takp-q> message for requesting a tak protocol version to server */
        fun buildRequestCot(uid: String?, version: Int? = 1): String {
            val (time, stale) = eventTimeStamps(30)
            return """
            <?xml version='1.0' encoding='UTF-8'?>
            <event version='2.0' uid='$uid' type='t-x-takp-q' how='m-g'
                   time='$time' start='$time' stale='$stale'>
              <point lat='0.0' lon='0.0' hae='0.0' ce='999999' le='999999'/>
              <detail>
                <TakControl>
                  <TakRequest version='$version'/>
                </TakControl>
              </detail>
            </event>
            """.trimIndent()
        }

    }

    /*
        /**
         * Builds and serializes a COT Event protobuf directly,
         * without ever creating or parsing XML.
         */
        @Throws(Exception::class)
        fun buildSerializedProtobuf(
            location: android.location.Location?,
            physioData: CotDispatchService.PhysioData
        ): ByteArray {
            // 0) Common values
            val now = Date()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val startTime = sdf.format(now)
            val staleTime = sdf.format(Date(now.time + 3L * currReportingIntervalInSecs * 1_000))

            val uid       = MainActivity.device_uid
            val lat       = location?.latitude  ?: 0.0
            val lon       = location?.longitude ?: 0.0
            val hae       = (location?.altitude ?: 0.0).toFloat()
            val ceTrim    = location?.accuracy?.takeIf { !it.isNaN() }?.toInt() ?: 9999
            val leTrim    = location?.verticalAccuracyMeters?.takeIf { !it.isNaN() }?.toInt() ?: 9999
            val course    = location?.bearing?.toInt() ?: 0
            val speed     = location?.speed?.toInt()   ?: 0

            val hr        = physioData.heartRate
            val st        = physioData.skinTemp.toInt()
            val bat       = physioData.battery

            // 1) Build sub-messages
            val point = Point.newBuilder()
                .setLat(lat)
                .setLon(lon)
                .setHae(hae)
                .setCe(ceTrim.toFloat())
                .setLe(leTrim.toFloat())
                .build()

            val group = Group.newBuilder()
                .setRole(roleValue)
                .setName(teamValue)
                .build()

            val track = Track.newBuilder()
                .setCourse(course.toFloat())
                .setSpeed(speed.toFloat())
                .build()

            val contact = Contact.newBuilder()
                .setCallsign(callsign)
                .build()

            val detail = Detail.newBuilder()
                .setGroup(group)
                .setTrack(track)
                .setContact(contact)
                .setRemarks("H:$hr;T:$st;B:$bat")
                .build()

            // 2) Build top-level Event
            val event = Event.newBuilder()
                .setVersion("2.0")
                .setUid(uid)
                .setType("a-f-G-U-C")
                .setTime(startTime)
                .setStart(startTime)
                .setStale(staleTime)
                .setHow("m-g")
                .setPoint(point)
                .setDetail(detail)
                .build()

            // 3) Serialize to protobuf bytes
            return event.toByteArray()
        }
    */

}

