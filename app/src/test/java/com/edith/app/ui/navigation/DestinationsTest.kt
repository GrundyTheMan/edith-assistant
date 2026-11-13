package com.edith.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationsTest {
    @Test
    fun destinationsInBottomBarOrder() {   // C25 added Home
        assertEquals(listOf("Chat", "Calendar", "Home", "Settings"), Destination.entries.map { it.label })
    }

    @Test
    fun chatIsTheStartDestination() {
        assertEquals(Destination.Chat, Destination.start)
    }

    @Test
    fun routesAreUnique() {
        assertEquals(Destination.entries.size, Destination.entries.map { it.route }.toSet().size)
    }
}
