package com.example.spendings.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// The app mirrors the layout of the month tab in "Бюджет / Budget YYYY".
// All amounts are euro cents.

/** One line of a section: a reason (empty for amount-only lists) and its amount. row < 0 = not uploaded yet. */
data class Entry(val row: Int, val label: String, val amountCents: Long?) {
    val uploading get() = row < 0
}

/** A spending section from row 1 of the sheet, e.g. "Сметки дом", "Пазар", "Кредит". */
data class Section(
    val name: String,
    val amountOnly: Boolean,
    val fixed: Boolean,
    val items: List<Entry>,
    val totalCents: Long,
)

/** A column pair under "Приходи", e.g. "Ади", "Иво". */
data class Person(val name: String, val items: List<Entry>, val totalCents: Long)

data class Balance(val previous: Long, val income: Long, val bills: Long, val leftover: Long)

data class MonthData(val sections: List<Section>, val persons: List<Person>, val balance: Balance) {
    companion object {
        fun parse(m: JSONObject): MonthData {
            val b = m.getJSONObject("balance")
            return MonthData(
                sections = m.getJSONArray("sections").objects().map {
                    Section(
                        name = it.getString("name"),
                        amountOnly = it.optBoolean("amountOnly"),
                        fixed = it.optBoolean("fixed"),
                        items = it.getJSONArray("items").objects().map(::entry),
                        totalCents = cents(it.optDouble("total", 0.0)),
                    )
                },
                persons = m.getJSONArray("persons").objects().map {
                    Person(it.getString("name"), it.getJSONArray("items").objects().map(::entry), cents(it.optDouble("total", 0.0)))
                },
                balance = Balance(
                    cents(b.optDouble("previous", 0.0)), cents(b.optDouble("income", 0.0)),
                    cents(b.optDouble("bills", 0.0)), cents(b.optDouble("leftover", 0.0)),
                ),
            )
        }

        private fun entry(o: JSONObject) = Entry(
            row = o.getInt("row"),
            label = o.optString("label", ""),
            amountCents = if (o.isNull("amount")) null else cents(o.getDouble("amount")),
        )
    }
}

data class TrendPoint(val sheet: String, val income: Long, val bills: Long, val leftover: Long)

/** What one screen shows: a month plus the context for the analysis. Built on the phone. */
data class LoadResult(
    val months: List<String>,
    val sheet: String,
    val month: MonthData,
    val previous: Map<String, Long>,
    val trend: List<TrendPoint>,
)

/**
 * Everything the phone knows: the list of month tabs and the data of the recent ones
 * (sent together by one "sync" request), so switching months needs no network.
 */
data class BudgetData(val months: List<String>, val current: String, val data: Map<String, MonthData>) {

    /** The screen data for one month, or null if that month isn't loaded. */
    fun view(sheet: String): LoadResult? {
        val month = data[sheet] ?: return null
        val i = months.indexOf(sheet)
        val prev = if (i > 0) data[months[i - 1]] else null
        val trend = months.subList(maxOf(0, i - 5), i + 1).mapNotNull { name ->
            data[name]?.balance?.let { TrendPoint(name, it.income, it.bills, it.leftover) }
        }
        return LoadResult(
            months = months,
            sheet = sheet,
            month = month,
            previous = prev?.sections?.associate { it.name to it.totalCents } ?: emptyMap(),
            trend = trend,
        )
    }

    companion object {
        /** Parses {months, current, data:{name: month}} as sent by the script's "sync". */
        fun parse(o: JSONObject): BudgetData {
            val d = o.getJSONObject("data")
            return BudgetData(
                months = o.getJSONArray("months").strings(),
                current = o.getString("current"),
                data = d.keys().asSequence().associateWith { MonthData.parse(d.getJSONObject(it)) },
            )
        }
    }
}

/** A change waiting to be sent to the sheet (kept on the phone while offline). */
data class PendingOp(val id: String, val body: JSONObject, val description: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("body", body).put("description", description)

    companion object {
        fun fromJson(o: JSONObject) = PendingOp(o.getString("id"), o.getJSONObject("body"), o.optString("description"))
    }
}

data class Settings(val scriptUrl: String = "", val secret: String = "") {
    val isConfigured get() = scriptUrl.isNotBlank() && secret.isNotBlank()
}

// ---- helpers ----

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun cents(euros: Double): Long =
    BigDecimal.valueOf(euros).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

fun euros(cents: Long): Double = BigDecimal.valueOf(cents, 2).toDouble()

// ---- formatting (Bulgarian conventions: DD.MM.YYYY, EUR) ----

private val bg: Locale = Locale.forLanguageTag("bg-BG")
private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun formatEur(cents: Long): String = String.format(bg, "%,.2f €", cents / 100.0)
fun formatDate(d: LocalDate): String = dateFmt.format(d)

val MONTHS_BG = listOf(
    "Януари", "Февруари", "Март", "Април", "Май", "Юни",
    "Юли", "Август", "Септември", "Октомври", "Ноември", "Декември",
)

/** Name of the sheet tab a date belongs to, e.g. "Октомври 2026". */
fun sheetNameFor(date: LocalDate): String = "${MONTHS_BG[date.monthValue - 1]} ${date.year}"

/** "Октомври 2026" → "Окт" for chart labels. */
fun shortMonth(sheet: String): String = sheet.take(3)

/** Accepts "12", "12.5", "12,50". Returns cents, or null if not a positive amount. */
fun parseAmount(text: String): Long? {
    val v = text.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull() ?: return null
    if (v.signum() <= 0 || v.scale() > 2) return null
    return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
}

/** Shows cents in an input field: 1250 → "12,50". */
fun amountInput(cents: Long): String = String.format(bg, "%.2f", cents / 100.0)
