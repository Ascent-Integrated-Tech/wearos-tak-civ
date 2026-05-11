package com.aitech.weartak_civ.cep.engine.lifecycle

import com.aitech.weartak_civ.cep.engine.CepEngine
import java.util.concurrent.atomic.AtomicReference

/**
 * Single ownership seam for the running [CepEngine] (per WEAR-508 [Update v1.1 — U1]).
 *
 * Holds an [AtomicReference] to the authoritative engine and exposes [swap] for
 * holder-mediated transactional replacement. All authoritative reads of "the current engine"
 * MUST go through this holder.
 *
 * The holder itself does NOT cancel the old engine; cancellation happens on the apply lane
 * after this swap returns the previous engine. That keeps the swap as a pure pointer
 * mutation — a critical-section-friendly operation.
 */
class CepEngineHolder {
    private val ref = AtomicReference<CepEngine?>(null)

    /** Authoritative read. Returns null until the first install. */
    fun current(): CepEngine? = ref.get()

    /**
     * Install [newEngine] as the authoritative engine. Returns the previously installed
     * engine (or null on first install) so the caller can cancel it after this returns.
     *
     * Per the design's "cancel old AFTER swap" rule, the swap itself does not stop the prior
     * engine's coroutines; the apply orchestrator does that immediately after this call
     * succeeds. Until that cancel completes, both engines are alive — only the new one is
     * authoritative.
     */
    fun swap(newEngine: CepEngine): CepEngine? = ref.getAndSet(newEngine)

    /**
     * Initialize the holder with the cold-start engine. Must be called exactly once before
     * any [swap]. Treated as a swap with no prior engine, so callers cannot accidentally
     * replace a running engine via [install].
     */
    fun install(initial: CepEngine): Boolean = ref.compareAndSet(null, initial)
}
