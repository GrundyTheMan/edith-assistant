package com.edith.app.testing

import com.edith.app.core.util.CowList

/** A list the test fakes add to from several threads and the tests read: what `Collections.synchronizedList` was, without the Java type. */
class SyncList<T> private constructor(private val items: CowList<T>) : AbstractList<T>() {
    constructor() : this(CowList())

    override val size: Int get() = items.snapshot().size
    override fun get(index: Int): T = items.snapshot()[index]
    operator fun plusAssign(item: T) = items.add(item)
}
