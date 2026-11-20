package com.example.spendings.data

import org.json.JSONObject
import java.time.LocalDate

/**
 * Shows changes that haven't reached the sheet yet, so a saved entry appears at once.
 * Mirrors what the script does; the next sync replaces it with the sheet's real values.
 */
fun BudgetData.withPending(pending: List<PendingOp>): BudgetData =
    pending.fold(this) { b, op -> try { b.apply(op.body) } catch (e: Exception) { b } }

private fun BudgetData.apply(body: JSONObject): BudgetData {
    val amount = if (body.isNull("amount")) null else cents(body.getDouble("amount"))
    return when (body.optString("action")) {
        "add" -> {
            val sheet = sheetNameFor(LocalDate.parse(body.getString("date")))
            val label = body.optString("label").trim()
            if (amount == null) this
            else if (body.optString("kind") == "income") {
                changeMonth(sheet, income = amount) { m -> m.addIncome(body.getString("person"), label, amount) }
            } else {
                changeMonth(sheet, bills = amount) { m -> m.addExpense(body.getString("section"), label, amount) }
            }
        }
        "edit" -> {
            val sheet = body.getString("sheet")
            val row = body.getInt("row")
            val old = data[sheet]?.let { m ->
                if (body.optString("kind") == "income") m.persons.firstOrNull { it.name == body.optString("person") }?.items
                else m.sections.firstOrNull { it.name == body.optString("section") }?.items
            }?.firstOrNull { it.row == row } ?: return this
            val delta = (amount ?: 0) - (old.amountCents ?: 0) // an empty reason being filled in has no amount yet
            if (body.optString("kind") == "income") {
                changeMonth(sheet, income = delta) { m -> m.editIncome(body.getString("person"), row, amount) }
            } else {
                changeMonth(sheet, bills = delta) { m -> m.editExpense(body.getString("section"), row, amount) }
            }
        }
        else -> this
    }
}

/** Applies a change to one month and moves the leftover of that month and every later one. */
private fun BudgetData.changeMonth(
    sheet: String,
    income: Long = 0,
    bills: Long = 0,
    change: (MonthData) -> MonthData?,
): BudgetData {
    val month = data[sheet] ?: return this // a month the phone hasn't seen yet: wait for the sync
    val changed = change(month) ?: return this
    val shift = income - bills
    val idx = months.indexOf(sheet)
    val newData = data.mapValues { (name, m) ->
        when {
            name == sheet -> changed.copy(
                balance = m.balance.copy(
                    income = m.balance.income + income,
                    bills = m.balance.bills + bills,
                    leftover = m.balance.leftover + shift,
                ),
            )
            idx >= 0 && months.indexOf(name) > idx -> m.copy(
                balance = m.balance.copy(previous = m.balance.previous + shift, leftover = m.balance.leftover + shift),
            )
            else -> m
        }
    }
    return copy(data = newData)
}

private fun MonthData.addExpense(section: String, label: String, amount: Long): MonthData? {
    val s = sections.firstOrNull { it.name == section } ?: return null
    val items = if (s.amountOnly) s.items + Entry(-1, "", amount)
    else addTo(s.items, label.ifBlank { "—" }, amount)
    return copy(sections = sections.map { if (it === s) it.copy(items = items, totalCents = it.totalCents + amount) else it })
}

private fun MonthData.addIncome(person: String, label: String, amount: Long): MonthData? {
    val p = persons.firstOrNull { it.name == person } ?: return null
    val items = addTo(p.items, label, amount)
    return copy(persons = persons.map { if (it === p) it.copy(items = items, totalCents = it.totalCents + amount) else it })
}

/** Same reason → amounts add up (like =a+b in the sheet); new reason → new line. */
private fun addTo(items: List<Entry>, label: String, amount: Long): List<Entry> {
    val i = items.indexOfFirst { it.label.equals(label, ignoreCase = true) }
    return if (i >= 0) items.mapIndexed { j, e -> if (j == i) e.copy(amountCents = (e.amountCents ?: 0) + amount) else e }
    else items + Entry(-1, label, amount)
}

private fun MonthData.editExpense(section: String, row: Int, amount: Long?): MonthData? {
    val s = sections.firstOrNull { it.name == section } ?: return null
    val old = (s.items.firstOrNull { it.row == row } ?: return null).amountCents ?: 0
    val items = if (amount == null && s.amountOnly) s.items.filterNot { it.row == row }
    else s.items.map { if (it.row == row) it.copy(amountCents = amount) else it }
    return copy(sections = sections.map {
        if (it === s) it.copy(items = items, totalCents = it.totalCents + (amount ?: 0) - old) else it
    })
}

private fun MonthData.editIncome(person: String, row: Int, amount: Long?): MonthData? {
    val p = persons.firstOrNull { it.name == person } ?: return null
    val old = (p.items.firstOrNull { it.row == row } ?: return null).amountCents ?: 0
    val items = p.items.map { if (it.row == row) it.copy(amountCents = amount) else it }
    return copy(persons = persons.map {
        if (it === p) it.copy(items = items, totalCents = it.totalCents + (amount ?: 0) - old) else it
    })
}
