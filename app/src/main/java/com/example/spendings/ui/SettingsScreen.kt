package com.example.spendings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.spendings.data.Settings

@Composable
fun SettingsScreen(
    settings: Settings,
    problem: String?,
    testing: Boolean,
    testLines: List<String>,
    onTest: (url: String, secret: String) -> Unit,
    onSave: (Settings) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(settings.scriptUrl) }
    var secret by rememberSaveable { mutableStateOf(settings.secret) }
    var showSecret by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (problem != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(problem, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Text("Budget sheet connection", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste the Web app URL (ends in /exec) and the secret key from the Apps Script in your " +
                "\"Бюджет / Budget\" sheet. Categories, reasons and income sources are read from the sheet itself.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Script URL") },
            placeholder = { Text("https://script.google.com/macros/s/…/exec") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it.trim() },
            label = { Text("Secret key") },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = { onTest(url, secret) },
            enabled = !testing && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Test connection")
        }
        if (testLines.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    testLines.forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (line.startsWith("✗")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        Button(onClick = { onSave(Settings(url, secret)) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Save and connect")
        }
    }
}
