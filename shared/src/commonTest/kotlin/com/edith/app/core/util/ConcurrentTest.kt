package com.edith.app.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConcurrentTest {
    @Test fun aMapHoldsAndForgets() {
        val m = CowMap<String, Int>()
        m["a"] = 1
        m["b"] = 2
        m["a"] = 3
        assertEquals(3, m["a"])
        assertEquals(setOf("a", "b"), m.keys)
        assertEquals(2, m.remove("b"))
        assertNull(m.remove("b"))
        assertNull(m["b"])
    }

    @Test fun removeKeysIfKeepsTheRest() {
        val m = CowMap<String, Int>()
        m["x:1"] = 1; m["y:2"] = 2; m["x:3"] = 3
        m.removeKeysIf { it.startsWith("x:") }
        assertEquals(setOf("y:2"), m.keys)
        assertEquals(listOf(2), m.values)
    }

    @Test fun aListKeepsItsOrder() {
        val l = CowList<Long>()
        l.add(5); l.add(7); l.add(6)
        assertEquals(listOf(5L, 7L, 6L), l.snapshot())
    }
}
