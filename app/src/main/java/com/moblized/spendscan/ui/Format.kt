package com.moblized.spendscan.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val FULL = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val SHORT = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy")
private val MONTH = DateTimeFormatter.ofPattern("MMMM")

fun LocalDate.full(): String = format(FULL)
fun LocalDate.short(): String = format(SHORT)
fun YearMonth.withYear(): String = format(MONTH_YEAR)
fun YearMonth.name(): String = format(MONTH)

fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
