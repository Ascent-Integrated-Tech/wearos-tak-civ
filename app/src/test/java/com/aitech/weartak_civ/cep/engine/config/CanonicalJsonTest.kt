package com.aitech.weartak_civ.cep.engine.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalJsonTest {

    @Test fun keysAreSortedLexicographically() {
        val a = """{"b": 1, "a": 2, "c": 3}"""
        val b = """{"a": 2, "c": 3, "b": 1}"""
        assertEquals(CanonicalJson.canonicalizeOrNull(a), CanonicalJson.canonicalizeOrNull(b))
    }

    @Test fun arrayOrderIsPreserved() {
        val left = CanonicalJson.canonicalizeOrNull("""{"r":[1,2,3]}""")
        val right = CanonicalJson.canonicalizeOrNull("""{"r":[3,2,1]}""")
        assertNotEquals(left, right)
    }

    @Test fun integerValuedDoublesEmitAsIntegers() {
        // 1.0 stored, must round-trip as "1"
        val s = CanonicalJson.canonicalize(mapOf("x" to 1.0))
        assertEquals("""{"x":1}""", s)
    }

    @Test fun whitespaceIgnored() {
        val a = """{ "a" : 1 ,
                     "b": [ 2, 3 ]   }"""
        val b = """{"b":[2,3],"a":1}"""
        assertEquals(CanonicalJson.canonicalizeOrNull(a), CanonicalJson.canonicalizeOrNull(b))
    }

    @Test fun hashIsStableForSemanticallyIdenticalJson() {
        val a = """{"engine":{"window_tick_ms":1000,"rule_eval_interval_ms":500},"features":[]}"""
        val b = """{"features":[],"engine":{"rule_eval_interval_ms":500,"window_tick_ms":1000}}"""
        assertEquals(CanonicalJson.hashRaw(a), CanonicalJson.hashRaw(b))
    }

    @Test fun hashChangesForChangedContent() {
        val a = """{"x":1}"""
        val b = """{"x":2}"""
        assertNotEquals(CanonicalJson.hashRaw(a), CanonicalJson.hashRaw(b))
    }

    @Test fun malformedReturnsNull() {
        assertNull(CanonicalJson.canonicalizeOrNull("not json"))
    }
}
