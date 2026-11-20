package com.example.spendings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendings.data.BudgetData
import com.example.spendings.data.Entry
import com.example.spendings.data.LoadResult
import com.example.spendings.data.LocalStore
import com.example.spendings.data.OfflineException
import com.example.spendings.data.PendingOp
import com.example.spendings.data.Settings
import com.example.spendings.data.SheetRefusedException
import com.example.spendings.data.SheetsApi
import com.example.spendings.data.euros
import com.example.spendings.data.formatEur
import com.example.spendings.data.withPending
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class UiState(
    val settings: Settings = Settings(),
    /** This month on the device — feeds the Add screen's categories and reasons. */
    val current: LoadResult? = null,
    /** The month shown on the Month and Analysis screens. */
    val viewed: LoadResult? = null,
    val pending: List<PendingOp> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    /** Last connection problem, shown until a sync succeeds. */
    val problem: String? = null,
    /** Result lines of "Test connection" in Settings. */
    val testLines: List<String> = emptyList(),
    val testing: Boolean = false,
)

/** Which list an entry belongs to. */
sealed interface Owner {
    data class Expense(val section: String) : Owner
    data class Income(val person: String) : Owner
}

/**
 * One "sync" request brings the list of months and the data of the recent ones, so
 * switching months and the analysis work without waiting. A saved change shows at once
 * (applied on the phone) and is sent in the background.
 */
class BudgetViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LocalStore(app)

    /** Last answer from the sheet (raw, so older months fetched later can be merged in). */
    private var serverJson: JSONObject? = store.loadBudget()
    private var server: BudgetData? = serverJson?.let(BudgetData::parse)
    private var viewedSheet: String? = null // null = follow the current month

    // All of this runs on the main thread, so plain flags are enough to coalesce syncs.
    private var running = false
    private var rerun = false
    private var rerunFresh = false

    private val _state = MutableStateFlow(UiState(settings = store.loadSettings(), pending = store.loadPending()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        publish()
        refresh(quiet = true)
    }

    // ---------- user actions ----------

    fun addExpense(section: String, reason: String, amountCents: Long, date: LocalDate) {
        val body = JSONObject()
            .put("action", "add").put("kind", "expense")
            .put("section", section).put("label", reason.trim())
            .put("amount", euros(amountCents)).put("date", date.toString())
        queue(body, "$section${if (reason.isNotBlank()) " · ${reason.trim()}" else ""} ${formatEur(amountCents)}")
    }

    fun addIncome(person: String, source: String, amountCents: Long, date: LocalDate) {
        val body = JSONObject()
            .put("action", "add").put("kind", "income")
            .put("person", person).put("label", source.trim())
            .put("amount", euros(amountCents)).put("date", date.toString())
        queue(body, "Income $person · ${source.trim()} ${formatEur(amountCents)}")
    }

    /** New amount for an entry; null removes the amount. */
    fun edit(sheet: String, owner: Owner, entry: Entry, newCents: Long?) {
        if (entry.uploading) return // its row in the sheet isn't known until it's uploaded
        val body = JSONObject().put("action", "edit").put("sheet", sheet)
            .put("row", entry.row).put("label", entry.label)
            .put("oldAmount", entry.amountCents?.let { euros(it) } ?: JSONObject.NULL)
            .put("amount", newCents?.let { euros(it) } ?: JSONObject.NULL)
        val name = when (owner) {
            is Owner.Expense -> { body.put("kind", "expense").put("section", owner.section); owner.section }
            is Owner.Income -> { body.put("kind", "income").put("person", owner.person); "Income ${owner.person}" }
        }
        val what = listOf(name, entry.label).filter { it.isNotBlank() }.joinToString(" · ")
        queue(body, if (newCents == null) "Remove $what" else "$what → ${formatEur(newCents)}")
    }

    /** Instant for the months the phone already has; older ones are fetched once. */
    fun selectMonth(sheet: String) {
        val b = server ?: return
        if (b.data.containsKey(sheet)) {
            viewedSheet = sheet.takeIf { it != b.current }
            publish()
            return
        }
        val settings = _state.value.settings
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val res = SheetsApi(settings.scriptUrl, settings.secret.trim()).month(sheet)
                serverJson?.getJSONObject("data")?.put(res.getString("sheet"), res.getJSONObject("data"))
                serverJson?.let { store.saveBudget(it); server = BudgetData.parse(it) }
                viewedSheet = sheet
                publish()
            } catch (e: OfflineException) {
                say("Offline — this month isn't saved on the phone yet")
            } catch (e: Exception) {
                say("Can't load $sheet: ${e.message}")
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun saveSettings(s: Settings) {
        store.saveSettings(s)
        _state.update { it.copy(settings = s, message = "Settings saved") }
        refresh(fresh = true)
    }

    /** Checks the given URL and key step by step without saving them. */
    fun testConnection(url: String, secret: String) {
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testLines = emptyList()) }
            val lines = try {
                SheetsApi(url, secret.trim()).diagnose(LocalDate.now().toString())
            } catch (e: Exception) {
                listOf("✗ ${e.message}")
            }
            _state.update { it.copy(testing = false, testLines = lines) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ---------- sync ----------

    private fun queue(body: JSONObject, description: String) {
        val op = PendingOp(UUID.randomUUID().toString(), body.put("opId", UUID.randomUUID().toString()), description)
        setPending(_state.value.pending + op)
        say("Saved")
        refresh(quiet = true)
    }

    /**
     * Sends waiting changes, then one sync. quiet = no "Up to date" message;
     * fresh = make the script re-read every month instead of using its cache (⟳ button).
     * Calls made while a sync runs are merged into one more sync afterwards.
     */
    fun refresh(quiet: Boolean = false, fresh: Boolean = false) {
        val settings = _state.value.settings
        if (!settings.isConfigured) {
            if (!quiet) say("Add the script URL and secret key in Settings first")
            return
        }
        if (running) {
            rerun = true
            rerunFresh = rerunFresh || fresh
            return
        }
        running = true
        viewModelScope.launch {
            var q = quiet
            var f = fresh
            while (true) {
                syncOnce(settings, q, f)
                if (!rerun) break
                rerun = false
                f = rerunFresh
                rerunFresh = false
                q = true
            }
            running = false
        }
    }

    private suspend fun syncOnce(settings: Settings, quiet: Boolean, fresh: Boolean) {
        _state.update { it.copy(loading = true) }
        try {
            val api = SheetsApi(settings.scriptUrl, settings.secret.trim())

            // 1. Upload changes in the order they were made. They stay in the queue (and on
            //    screen) until a sync that includes them succeeds; the script ignores repeats.
            val sent = mutableSetOf<String>()
            for (op in _state.value.pending.toList()) {
                try {
                    api.send(op.body)
                    sent += op.id
                } catch (e: SheetRefusedException) {
                    say("Not saved — ${op.description}: ${e.message}")
                    setPending(_state.value.pending.filterNot { it.id == op.id })
                }
            }

            // 2. One request for all recent months.
            val json = api.sync(LocalDate.now().toString(), fresh)
            if (!fresh) {
                // Keep older months fetched earlier that this sync doesn't include.
                val old = serverJson?.optJSONObject("data")
                val new = json.getJSONObject("data")
                old?.keys()?.forEach { k -> if (!new.has(k)) new.put(k, old.get(k)) }
            }
            serverJson = json
            server = BudgetData.parse(json)
            store.saveBudget(json)
            setPending(_state.value.pending.filterNot { it.id in sent })
            _state.update { it.copy(problem = null) }
            if (!quiet) say("Up to date")
        } catch (e: OfflineException) {
            _state.update { it.copy(problem = "Offline — changes will be sent when the phone is online") }
            if (!quiet || _state.value.pending.isNotEmpty()) say("Offline — changes will be sent later")
        } catch (e: Exception) {
            // Wrong URL, access or key: say exactly what's wrong, keep the changes.
            _state.update { it.copy(problem = e.message ?: e.javaClass.simpleName) }
            say("Can't reach the sheet: ${e.message}")
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** Rebuilds what the screens show: the sheet's data plus changes not uploaded yet. */
    private fun publish() {
        val shown = server?.withPending(_state.value.pending)
        val current = shown?.view(shown.current)
        val viewed = viewedSheet?.let { shown?.view(it) } ?: current
        _state.update { it.copy(current = current, viewed = viewed) }
    }

    private fun setPending(list: List<PendingOp>) {
        store.savePending(list)
        _state.update { it.copy(pending = list) }
        publish()
    }

    private fun say(msg: String) = _state.update { it.copy(message = msg) }
}
