package com.example.spendings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.spendings.data.LoadResult
import com.example.spendings.data.formatEur
import com.example.spendings.data.shortMonth
import kotlin.math.abs
import kotlin.math.roundToInt

private val palette = listOf(
    Color(0xFF2A9D8F), Color(0xFFE76F51), Color(0xFF457B9D), Color(0xFFE9C46A),
    Color(0xFF8E6C8A), Color(0xFF6A994E), Color(0xFFF4A261), Color(0xFF9C6644),
)
private fun colorFor(name: String) = palette[Math.floorMod(name.hashCode(), palette.size)]

@Composable
fun AnalysisScreen(data: LoadResult?, onSelectMonth: (String) -> Unit) {
    if (data == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val m = data.month
    val bills = m.balance.bills
    val sections = m.sections.filter { it.totalCents > 0 }.sortedByDescending { it.totalCents }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MonthSwitcher(data.months, data.sheet, onSelectMonth)
        BalanceCard(m.balance)

        // Bills by section, with change vs previous month
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Where the money went", style = MaterialTheme.typography.titleMedium)
                if (sections.isEmpty()) Text("No bills yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                sections.forEach { s ->
                    val share = if (bills > 0) s.totalCents.toFloat() / bills else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f))
                            Text("${(share * 100).roundToInt()}%  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatEur(s.totalCents), fontWeight = FontWeight.SemiBold)
                        }
                        Bar(share, colorFor(s.name))
                        val before = data.previous[s.name]
                        if (before != null && before > 0) {
                            val p = ((s.totalCents - before) * 100.0 / before).roundToInt()
                            Text(
                                "${if (p >= 0) "▲" else "▼"} ${abs(p)}% vs last month (${formatEur(before)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (p > 0) BillsColor else IncomeColor,
                            )
                        }
                    }
                }
            }
        }

        // Income by person
        if (m.persons.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Income", style = MaterialTheme.typography.titleMedium)
                    m.persons.forEach { p ->
                        Row {
                            Text(p.name, Modifier.weight(1f))
                            Text(formatEur(p.totalCents), fontWeight = FontWeight.SemiBold, color = IncomeColor)
                        }
                        p.items.filter { (it.amountCents ?: 0) > 0 }.forEach { e ->
                            Row(Modifier.padding(start = 12.dp)) {
                                Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatEur(e.amountCents ?: 0), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // Income vs bills over the last months (tap a month to open it)
        if (data.trend.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Income vs bills", style = MaterialTheme.typography.titleMedium)
                    val max = data.trend.maxOf { maxOf(it.income, it.bills) }.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.trend.forEach { t ->
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable { onSelectMonth(t.sheet) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(
                                    Modifier.weight(1f).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    VBar(t.income.toFloat() / max, IncomeColor, Modifier.weight(1f))
                                    VBar(t.bills.toFloat() / max, BillsColor, Modifier.weight(1f))
                                }
                                Text(
                                    shortMonth(t.sheet),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (t.sheet == data.sheet) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    "${(t.leftover / 100.0).roundToInt()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (t.leftover < 0) BillsColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(IncomeColor, "Income"); Spacer(Modifier.width(16.dp)); Legend(BillsColor, "Bills")
                        Spacer(Modifier.weight(1f))
                        Text("bottom number = leftover €", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) { Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color)) }
}

@Composable
private fun VBar(fraction: Float, color: Color, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight(fraction.coerceIn(0.01f, 1f))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(color),
    )
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text("  $label", style = MaterialTheme.typography.labelSmall)
    }
}
