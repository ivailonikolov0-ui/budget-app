package com.example.spendings.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phone-side storage: connection settings, the last synced months (so the app
 * opens instantly and works offline) and changes not yet sent to the sheet.
 */
class LocalStore(context: Context) {
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        scriptUrl = prefs.getString("scriptUrl", "") ?: "",
        secret = prefs.getString("secret", "") ?: "",
    )

    fun saveSettings(s: Settings) {
        prefs.edit().putString("scriptUrl", s.scriptUrl).putString("secret", s.secret).apply()
    }

    /** The raw sync answer {months, current, data}; parsed with BudgetData.parse. */
    fun loadBudget(): JSONObject? = read("budget.json")?.let {
        try { JSONObject(it).also { o -> BudgetData.parse(o) } } catch (e: Exception) { null }
    }

    fun saveBudget(json: JSONObject) = write("budget.json", json.toString())

    fun loadPending(): List<PendingOp> = read("pending.json")?.let {
        try { JSONArray(it).objects().map(PendingOp::fromJson) } catch (e: Exception) { emptyList() }
    } ?: emptyList()

    fun savePending(list: List<PendingOp>) =
        write("pending.json", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name)) // atomic replace
    }
}
