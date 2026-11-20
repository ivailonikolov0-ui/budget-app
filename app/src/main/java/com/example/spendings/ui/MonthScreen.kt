package com.example.spendings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.spendings.Owner
import com.example.spendings.data.Entry
import com.example.spendings.data.LoadResult
import com.example.spendings.data.PendingOp
import com.example.spendings.data.formatEur

/**
 * The month tab as in the sheet: balance on top, then income and every section.
 * Tap a section to open it, tap an amount to change or remove it.
 */
@Composable
fun MonthScreen(
    data: LoadResult?,
    pending: List<PendingOp>,
    onSelectMonth: (String) -> Unit,
    onEdit: (sheet: String, owner: Owner, entry: Entry, newCents: Long?) -> Unit,
) {
    if (data == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var editing by remember { mutableStateOf<Pair<Owner, Entry>?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonthSwitcher(data.months, data.sheet, onSelectMonth)
        BalanceCard(data.month.balance)

        if (pending.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Waiting to upload (${pending.size})", fontWeight = FontWeight.SemiBold)
                    pending.forEach { Text(it.description, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        data.month.persons.forEach { p ->
            Group("Income · ${p.name}", p.totalCents, p.items, IncomeColor, startOpen = true) { e ->
                editing = Owner.Income(p.name) to e
            }
        }
        data.month.sections.forEach { s ->
            Group(s.name, s.totalCents, s.items, null, startOpen = false) { e ->
                editing = Owner.Expense(s.name) to e
            }
        }
    }

    editing?.let { (owner, entry) ->
        val name = when (owner) { is Owner.Expense -> owner.section; is Owner.Income -> "Income · ${owner.person}" }
        EditAmountDialog(
            title = entry.label.ifBlank { name },
            subtitle = "$name · ${data.sheet}",
            currentCents = entry.amountCents,
            onSave = { onEdit(data.sheet, owner, entry, it); editing = null },
            onRemove = { onEdit(data.sheet, owner, entry, null); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun Group(
    title: String,
    totalCents: Long,
    items: List<Entry>,
    color: androidx.compose.ui.graphics.Color?,
    startOpen: Boolean,
    onTap: (Entry) -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val filled = items.filter { it.amountCents != null }
    // Reasons without an amount yet (e.g. "Ток") — tap to fill them in.
    val empty = items.filter { it.amountCents == null && it.label.isNotBlank() }

    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatEur(totalCents), fontWeight = FontWeight.SemiBold, color = color ?: MaterialTheme.colorScheme.onSurface)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
        if (open) {
            if (filled.isEmpty() && empty.isEmpty()) {
                Text("Nothing yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp))
            }
            filled.forEach { e -> EntryRow(e, onTap) }
            empty.forEach { e -> EntryRow(e, onTap) }
            Box(Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun EntryRow(e: Entry, onTap: (Entry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !e.uploading) { onTap(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.label.ifBlank { "—" } + if (e.uploading) "  ↑ uploading" else "", modifier = Modifier.weight(1f),
            color = if (e.amountCents == null || e.uploading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Text(e.amountCents?.let(::formatEur) ?: "add", color = if (e.amountCents == null)
            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
