package com.edith.app.data.calendar

import java.util.Date

data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String?,
    val start: Date,
    val end: Date
)
