package com.example.spendings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.spendings.data.LoadResult
import com.example.spendings.data.formatDate
import com.example.spendings.data.formatEur
import com.example.spendings.data.parseAmount
import com.example.spendings.data.sheetNameFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Amount → category → (reason) → Save.
 * Categories and reasons come from this month's tab in the sheet.
 * The entry goes to the tab of the chosen date (today by default).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddScreen(
    current: LoadResult?,
    onExpense: (section: String, reason: String, cents: Long, date: LocalDate) -> Unit,
    onIncome: (person: String, source: String, cents: Long, date: LocalDate) -> Unit,
) {
    if (current == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Connect your budget sheet in Settings (gear icon) to start.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val sections = current.month.sections.filterNot { it.fixed } // Кредит is added automatically
    val persons = current.month.persons

    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = expense, 1 = income
    var amount by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(sections.firstOrNull()?.name ?: "") }
    var person by rememberSaveable { mutableStateOf(persons.firstOrNull()?.name ?: "") }
    var reason by rememberSaveable { mutableStateOf("") }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var pickDate by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val date = LocalDate.ofEpochDay(epochDay)
    val cents = parseAmount(amount)
    val isIncome = mode == 1
    val selectedSection = sections.firstOrNull { it.name == section }
    val needsReason = isIncome // income always needs a source; expenses may skip the reason
    val showReason = isIncome || selectedSection?.amountOnly == false
    // Reasons already in this month's sheet, with what's entered so far (shown in the dropdown).
    val suggestions = (if (isIncome) persons.firstOrNull { it.name == person }?.items
    else selectedSection?.items).orEmpty()
        .filter { it.label.isNotBlank() && it.label != "—" }
        .groupBy { it.label }
        .map { (label, list) -> label to list.mapNotNull { it.amountCents }.takeIf { it.isNotEmpty() }?.sum() }
    val canSave = cents != null && (if (isIncome) person.isNotEmpty() else section.isNotEmpty()) &&
        (!needsReason || reason.isNotBlank())

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(sections, persons) {
        if (sections.none { it.name == section }) section = sections.firstOrNull()?.name ?: ""
        if (persons.none { it.name == person }) person = persons.firstOrNull()?.name ?: ""
    }

    fun save() {
        if (!canSave || cents == null) return
        if (isIncome) onIncome(person, reason, cents, date)
        else onExpense(section, if (showReason) reason else "", cents, date)
        amount = ""
        reason = ""
        focus.requestFocus()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Expense", "Income").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = mode == i,
                    onClick = { mode = i; reason = "" },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
            suffix = { Text("€") },
            singleLine = true,
            isError = amount.isNotBlank() && cents == null,
            textStyle = MaterialTheme.typography.headlineMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )

        // Category (expense) or person (income)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isIncome) {
                persons.forEach { p ->
                    FilterChip(selected = p.name == person, onClick = { person = p.name; reason = "" },
                        label = { Text(p.name, Modifier.padding(vertical = 8.dp)) })
                }
            } else {
                sections.forEach { s ->
                    FilterChip(selected = s.name == section, onClick = { section = s.name; reason = "" },
                        label = { Text(s.name, Modifier.padding(vertical = 8.dp)) })
                }
            }
        }

        // Reason / source: pick one already in the sheet from the dropdown, or type a new one.
        if (showReason) {
            ReasonDropdown(
                value = reason,
                onValueChange = { reason = it },
                label = if (isIncome) "Source" else "Reason (optional)",
                options = suggestions,
                onDone = { save() },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pickDate = true }) {
                Icon(Icons.Filled.DateRange, null)
                Text(if (date == LocalDate.now()) "  Today, ${formatDate(date)}" else "  ${formatDate(date)}")
            }
            Text("→ ${sheetNameFor(date)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Button(onClick = { save() }, enabled = canSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Save", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            "Left this month: ${formatEur(current.month.balance.leftover)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    if (pickDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        epochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

/**
 * Text field with a dropdown of the reasons already in the sheet. Typing filters the list;
 * a reason that isn't there yet is simply typed and saved as a new line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasonDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<Pair<String, Long?>>,
    onDone: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = if (value.isBlank()) options
    else options.filter { it.first.contains(value.trim(), ignoreCase = true) }
    val exact = options.any { it.first.equals(value.trim(), ignoreCase = true) }

    ExposedDropdownMenuBox(expanded = expanded && filtered.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && filtered.isNotEmpty()) },
            supportingText = {
                if (value.isNotBlank() && !exact) Text("New reason — it will be added to the sheet")
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { expanded = false; onDone() }),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            filtered.forEach { (name, cents) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = {
                        if (cents != null) Text(formatEur(cents), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = { onValueChange(name); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
