package com.aitech.weartak_civ.data

/** Tracks deletions this device sent to its paired peer until the peer acknowledges or times out. */
class PointDeletionAcks {
    enum class Outcome { APPLIED, REJECTED, NOT_ACKNOWLEDGED }
    data class Result(val uid: String, val outcome: Outcome)

    private data class Key(val uid: String, val token: String, val generation: Long)
    private val pending = mutableSetOf<Key>()

    @Synchronized fun sent(uid: String, token: String, generation: Long) {
        pending.add(Key(uid, token, generation))
    }

    /** Returns null for an unknown, duplicate, or previous-connection acknowledgement. */
    @Synchronized fun acknowledged(uid: String, token: String, generation: Long, applied: Boolean): Result? =
        if (pending.remove(Key(uid, token, generation))) {
            Result(uid, if (applied) Outcome.APPLIED else Outcome.REJECTED)
        } else null

    /** Returns null when an acknowledgement already arrived. */
    @Synchronized fun timedOut(uid: String, token: String, generation: Long): Result? =
        if (pending.remove(Key(uid, token, generation))) Result(uid, Outcome.NOT_ACKNOWLEDGED) else null

    @Synchronized fun clear() = pending.clear()
}
