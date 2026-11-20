package com.example.spendings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.spendings.data.Balance
import com.example.spendings.data.amountInput
import com.example.spendings.data.formatEur
import com.example.spendings.data.parseAmount

val IncomeColor = Color(0xFF2E7D5B)
val BillsColor = Color(0xFFC0503A)

/** "‹  Ноември 2026  ›" — steps through the month tabs that exist in the sheet. */
@Composable
fun MonthSwitcher(months: List<String>, sheet: String, onSelect: (String) -> Unit) {
    val i = months.indexOf(sheet)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(months[i - 1]) }, enabled = i > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
        }
        Text(sheet, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { onSelect(months[i + 1]) }, enabled = i >= 0 && i < months.size - 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

/** Previous leftover + income − bills = leftover. */
@Composable
fun BalanceCard(b: Balance) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BalanceLine("Leftover from last month", b.previous)
            BalanceLine("+ Income", b.income, IncomeColor)
            BalanceLine("− Bills", b.bills, BillsColor)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Leftover this month", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    formatEur(b.leftover),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (b.leftover < 0) BillsColor else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BalanceLine(label: String, cents: Long, color: Color? = null) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(formatEur(cents), color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Change the amount of one entry, or remove it. */
@Composable
fun EditAmountDialog(
    title: String,
    subtitle: String,
    currentCents: Long?,
    onSave: (Long) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentCents?.let(::amountInput) ?: "") }
    val cents = parseAmount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount") },
                    suffix = { Text("€") },
                    singleLine = true,
                    isError = text.isNotBlank() && cents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { cents?.let(onSave) }, enabled = cents != null) { Text("Save") } },
        dismissButton = {
            Row {
                if (currentCents != null) TextButton(onClick = onRemove) { Text("Remove", color = BillsColor) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
