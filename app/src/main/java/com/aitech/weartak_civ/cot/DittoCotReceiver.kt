package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import android.util.Log
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus

/** Data-only ingress. No plugin, configuration, command runtime, channel or package callbacks. */
internal class DittoCotReceiver(
    private val repository: DataRepository,
    private val bloodhounds: BloodhoundRepository,
    private val journal: SharedPreferences,
    private val deviceUid: () -> String,
    private val callsign: () -> String,
    private val localPeer: () -> String,
    private val postChat: (TakChatEvent) -> Unit = { EventBus.getDefault().post(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) }
) {
    private val mutex = Mutex()
    private val seen = mutableMapOf<String, Boolean>()
    private val watermarks = journal.all.toMutableMap()
    private val ingestor = TakServerCotIngestor(
        sink = object : TakServerCotSink {
            override suspend fun onChat(event: TakChatEvent) {
                currentCoroutineContext().ensureActive()
                postChat(event)
            }
            override suspend fun onGeofence(geofence: GeofenceCot) = Unit
            override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                if (event.detail.creator?.uid == deviceUid()) return
                repository.applyReceivedUserEvent(event, envelope.sourceServerAddress,
                    envelope.sourceServerId, envelope.receivedAt)
            }
            override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                if (event.detail.creator?.uid == deviceUid() ||
                    repository.droppedMarkerList.value.any { it.uid == event.uid } ||
                    repository.isDeletedLocalPoint(event.uid)) return
                repository.receiveMarkerEvent(event, bloodhounds, envelope.sourceServerAddress,
                    envelope.sourceServerId, envelope.receivedAt)
            }
            override suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                repository.upsertTakRoute(route)
            }
            override suspend fun onEmergency(event: CotEmergencyEvent, envelope: TakServerCotEnvelope) {
                currentCoroutineContext().ensureActive()
                bloodhounds.applyEmergencyEvent(event, envelope.sourceServerAddress,
                    envelope.sourceServerId, envelope.receivedAt, deviceUid())
            }
        },
        isChatEnabled = { repository.getEnableChatService.first() },
        isGeofenceEnabled = { false },
        deviceUid = deviceUid,
        deviceCallsign = callsign,
        logger = log
    )

    suspend fun receive(collection: String, documents: List<Map<String, Any?>>) = mutex.withLock {
        require(collection in DittoCotDocument.COLLECTIONS)
        for (raw in documents) {
            currentCoroutineContext().ensureActive()
            // A hidden/removed document is not a signed deletion instruction.
            try {
                if (raw["_r"] == true || raw["visible"] == false || raw["f"] == false) continue
                val doc = DittoCotDocument.decode(raw)
                require(collection != "track" || CotMessageParser.isUserPositionType(doc.type)) {
                    "Non-PLI document in Ditto track"
                }
                require(collection != "alert" || CotMessageParser.isEmergencyType(doc.type)) {
                    "Non-emergency document in Ditto alert"
                }
                if (doc.peer == localPeer() || doc.author == deviceUid() || doc.uid == deviceUid()) continue
                val currentTime = now()
                require(doc.time <= currentTime + MAX_FUTURE_MS) { "Future-dated Ditto event" }
                val expired = doc.stale <= currentTime
                if (expired && doc.type == "b-t-f") continue
                val key = doc.key
                val fingerprint = doc.fingerprint
                val previous = (watermarks[key] as? String)?.split(':', limit = 3)
                if (previous != null) {
                    val time = previous[0].toLong()
                    if (doc.time < time ||
                        (doc.time == time && (previous[2] != fingerprint ||
                            seen[key] == expired || doc.type == "b-t-f"))) continue
                }
                if (key !in watermarks && watermarks.size >= MAX_JOURNAL_ENTRIES) {
                    pruneJournal(currentTime)
                    require(watermarks.size < MAX_JOURNAL_ENTRIES) { "Ditto replay journal full" }
                }
                val result = if (expired && doc.type == "b-m-r") {
                    if (TakRouteCotParser.parse(doc.xml, doc.sourceId) == null) {
                        log("Rejected malformed expired Ditto route", null)
                        continue
                    }
                    repository.removeTakRoute(doc.sourceId, doc.uid)
                    TakServerCotIngestResult.ROUTE
                } else {
                    ingestor.ingest(TakServerCotEnvelope(
                        sourceServerId = doc.sourceId, sourceServerAddress = "Ditto",
                        receivedAt = currentTime, rawXml = doc.xml
                    ))
                }
                if (result == TakServerCotIngestResult.MALFORMED ||
                    result == TakServerCotIngestResult.UNSUPPORTED) continue
                currentCoroutineContext().ensureActive()
                // Persist chat replay suppression; live state is rehydrated once on a new receiver.
                val retainUntil = maxOf(doc.stale, previous?.get(1)?.toLong() ?: doc.stale)
                val watermark = "${doc.time}:$retainUntil:$fingerprint"
                check(journal.edit().putString(key, watermark).commit()) {
                    "Could not persist Ditto replay watermark"
                }
                watermarks[key] = watermark
                seen[key] = expired
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("Rejected Ditto $collection document: ${error.message}", error)
            }
        }
    }

    private fun pruneJournal(time: Long) {
        val editor = journal.edit()
        val expired = watermarks.filter { (_, value) ->
            val stale = (value as? String)?.split(':', limit = 3)?.getOrNull(1)?.toLongOrNull()
            stale != null && stale <= time
        }
        expired.keys.forEach(editor::remove)
        check(editor.commit()) { "Could not prune Ditto replay journal" }
        expired.keys.forEach { watermarks.remove(it); seen.remove(it) }
    }

    companion object {
        private const val TAG = "WearTAK-DittoReceive"
        const val MAX_JOURNAL_ENTRIES = 8192
        const val MAX_FUTURE_MS = 300_000L
    }
}
