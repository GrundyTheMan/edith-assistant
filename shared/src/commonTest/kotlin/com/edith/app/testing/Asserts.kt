package com.edith.app.testing

import kotlin.test.fail as kFail

/**
 * JUnit-style assertions for tests that run on every platform (G2): the message comes first, as in JUnit 4, so a test moved here from `app/` keeps
 * its body. (kotlin.test puts the message last.) Only what the moved tests use.
 */
fun assertEquals(expected: Any?, actual: Any?) { kotlin.test.assertEquals(expected, actual) }
fun assertEquals(message: String, expected: Any?, actual: Any?) { kotlin.test.assertEquals(expected, actual, message) }
fun assertEquals(expected: Double, actual: Double, delta: Double) { if (kotlin.math.abs(expected - actual) > delta) kFail("expected $expected but was $actual (delta $delta)") }
fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) { if (kotlin.math.abs(expected - actual) > delta) kFail("$message: expected $expected but was $actual (delta $delta)") }
fun assertEquals(expected: Float, actual: Float, delta: Float) { if (kotlin.math.abs(expected - actual) > delta) kFail("expected $expected but was $actual (delta $delta)") }
fun assertEquals(expected: Long, actual: Long) { kotlin.test.assertEquals(expected, actual) }
fun assertNotEquals(unexpected: Any?, actual: Any?) { kotlin.test.assertNotEquals(unexpected, actual) }
fun assertNotEquals(message: String, unexpected: Any?, actual: Any?) { kotlin.test.assertNotEquals(unexpected, actual, message) }
fun assertTrue(condition: Boolean) { kotlin.test.assertTrue(condition) }
fun assertTrue(message: String, condition: Boolean) { kotlin.test.assertTrue(condition, message) }
fun assertFalse(condition: Boolean) { kotlin.test.assertFalse(condition) }
fun assertFalse(message: String, condition: Boolean) { kotlin.test.assertFalse(condition, message) }
fun assertNull(actual: Any?) { kotlin.test.assertNull(actual) }
fun assertNull(message: String, actual: Any?) { kotlin.test.assertNull(actual, message) }
fun assertNotNull(actual: Any?) { kotlin.test.assertNotNull(actual) }
fun assertNotNull(message: String, actual: Any?) { kotlin.test.assertNotNull(actual, message) }
fun assertSame(expected: Any?, actual: Any?) { kotlin.test.assertSame(expected, actual) }
fun assertArrayEquals(expected: ByteArray, actual: ByteArray) { kotlin.test.assertContentEquals(expected, actual) }
fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) { kotlin.test.assertContentEquals(expected, actual, message) }
fun fail(message: String): Nothing = kFail(message)
