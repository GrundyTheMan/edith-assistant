package com.edith.app.testing

import com.edith.app.core.dispatchers.AppDispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** All three dispatchers are the same test dispatcher, so work runs in the test's virtual time (C02 §8). */
fun testDispatchers(dispatcher: TestDispatcher = UnconfinedTestDispatcher()) =
    AppDispatchers(main = dispatcher, io = dispatcher, default = dispatcher)
