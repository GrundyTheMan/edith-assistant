package com.edith.app.core.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A map several threads may use at once (K2.4: `ConcurrentHashMap` is a Java type). It keeps one immutable map and swaps it for a changed
 * copy, so a read never sees half a change. Fine for a dozen entries (a home's devices); not for thousands.
 */
@OptIn(ExperimentalAtomicApi::class)
class CowMap<K, V> {
    private val ref = AtomicReference<Map<K, V>>(emptyMap())

    operator fun get(key: K): V? = ref.load()[key]
    operator fun set(key: K, value: V) { update { it + (key to value) } }

    /** Removes [key] and returns what it held, or null. */
    fun remove(key: K): V? {
        var old: V? = null
        update { m -> old = m[key]; m - key }
        return old
    }

    val values: List<V> get() = ref.load().values.toList()
    val keys: Set<K> get() = ref.load().keys

    /** Removes every entry whose key matches [predicate]. */
    fun removeKeysIf(predicate: (K) -> Boolean) { update { m -> m.filterKeys { !predicate(it) } } }

    private inline fun update(change: (Map<K, V>) -> Map<K, V>) {
        while (true) {
            val now = ref.load()
            if (ref.compareAndSet(now, change(now))) return
        }
    }
}

/** A list several threads may add to and read at once (K2.4: `CopyOnWriteArrayList` is a Java type). Same idea as [CowMap]. */
@OptIn(ExperimentalAtomicApi::class)
class CowList<T> {
    private val ref = AtomicReference<List<T>>(emptyList())

    fun add(item: T) {
        while (true) {
            val now = ref.load()
            if (ref.compareAndSet(now, now + item)) return
        }
    }

    /** The items as they are now. */
    fun snapshot(): List<T> = ref.load()
}
