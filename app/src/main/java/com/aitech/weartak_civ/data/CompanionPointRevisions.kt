package com.aitech.weartak_civ.data

import org.json.JSONObject

/** Only revisions exchanged on the current BLE connection authorize a shared-point removal. */
class CompanionPointRevisions {
    data class Revision(val token: String, val time: Long, val local: Boolean, val generation: Long)
    private val revisions = mutableMapOf<String, Revision>()

    @Synchronized fun remember(uid: String, revision: Revision) {
        if (uid.isNotBlank() && validToken(revision.token) && revision.time > 0) {
            revisions[uid] = revision
        }
    }

    @Synchronized fun current(uid: String, generation: Long): Revision? =
        revisions[uid]?.takeIf { it.generation == generation }

    @Synchronized fun forget(uid: String) { revisions.remove(uid) }

    companion object {
        fun validToken(token: String) = token.matches(Regex("[a-zA-Z0-9-]{1,64}"))
        fun deletion(uid: String, token: String) = JSONObject()
            .put("msg_type", "marker_delete")
            .put("payload", JSONObject().put("targetUid", uid).put("revision", token))
        fun deletionAck(uid: String, token: String, applied: Boolean) = JSONObject()
            .put("msg_type", "marker_delete_ack")
            .put("payload", JSONObject().put("targetUid", uid).put("revision", token)
                .put("result", if (applied) RESULT_APPLIED else RESULT_REJECTED))
        fun parseDeletionAck(payload: JSONObject?): Triple<String, String, Boolean>? {
            val (uid, token) = parseDeletion(payload) ?: return null
            val applied = when (payload?.opt("result")) {
                RESULT_APPLIED -> true
                RESULT_REJECTED -> false
                else -> return null
            }
            return Triple(uid, token, applied)
        }
        const val RESULT_APPLIED = "applied"
        const val RESULT_REJECTED = "rejected"
        fun parseDeletion(payload: JSONObject?): Pair<String, String>? {
            val uid = payload?.opt("targetUid") as? String ?: return null
            val token = payload.opt("revision") as? String ?: return null
            return (uid to token).takeIf { uid.isNotBlank() && uid.length <= 256 && validToken(token) }
        }
    }
}
