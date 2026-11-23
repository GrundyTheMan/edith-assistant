package com.edith.app.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedGreetingTest {
    @Test
    fun namesThePlatform() {
        assertEquals("Edith on iPhone", SharedGreeting("iPhone").hello())
    }
}
