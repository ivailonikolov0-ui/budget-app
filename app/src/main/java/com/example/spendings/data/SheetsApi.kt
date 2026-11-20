package com.example.spendings.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The phone has no connection to Google right now. Changes stay queued. */
class OfflineException(message: String) : IOException(message)

/** Google or the script can't be used with these settings (wrong URL, access, key…). Changes stay queued. */
class ConnectionProblem(message: String) : Exception(message)

/** The script understood the change but refused it (e.g. the sheet was edited meanwhile). */
class SheetRefusedException(message: String) : Exception(message)

/**
 * Talks to the Apps Script web app attached to the budget sheet.
 * Requests are POSTed; if Google refuses an anonymous POST (401/403/405), the same request is
 * retried as a GET (the script answers both).
 */
class SheetsApi(rawUrl: String, private val secret: String) {

    private val scriptUrl: String = normalizeUrl(rawUrl)

    /** All month names + data of the current month and the 12 before it. fresh = re-read everything. */
    suspend fun sync(today: String, fresh: Boolean): JSONObject =
        call(JSONObject().put("action", "sync").put("today", today).put("fresh", fresh))

    /** One older month that sync didn't send: {sheet, data}. */
    suspend fun month(sheet: String): JSONObject =
        call(JSONObject().put("action", "month").put("sheet", sheet))

    suspend fun send(body: JSONObject): JSONObject = call(JSONObject(body.toString()))

    /** Step-by-step check for the Settings screen. Never throws. */
    suspend fun diagnose(today: String): List<String> {
        val lines = mutableListOf("URL: …${scriptUrl.takeLast(24)}")
        try {
            val r = withContext(Dispatchers.IO) { request("GET", scriptUrl, null) }
            lines += when {
                r.code in 200..299 && r.body.contains("backend is running") -> "✓ Web app reachable"
                r.code in 200..299 && looksLikeSignIn(r) -> "✗ Web app asks for a Google sign-in → set \"Who has access\" to \"Anyone\" (Всички)"
                else -> "✗ Web app answered HTTP ${r.code} at ${r.host} → ${httpHint(r.code)}"
            }
        } catch (e: Exception) {
            lines += "✗ ${describe(e)}"
            return lines
        }
        try {
            val res = call(JSONObject().put("action", "sync").put("today", today))
            lines += "✓ Secret key accepted"
            lines += "✓ Sheet read: ${res.optString("current")} (${res.optJSONObject("data")?.length() ?: 0} months)"
        } catch (e: Exception) {
            lines += "✗ ${e.message ?: describe(e)}"
        }
        return lines
    }

    // ---------- transport ----------

    private class Response(val code: Int, val body: String, val host: String, val contentType: String)

    private suspend fun call(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        body.put("secret", secret)
        try {
            var r = request("POST", scriptUrl, body.toString())
            if (r.code == 401 || r.code == 403 || r.code == 405) {
                // Some Google front-ends refuse anonymous POSTs; the script also accepts GET ?p=…
                val getUrl = scriptUrl + "?p=" + URLEncoder.encode(body.toString(), "UTF-8")
                r = request("GET", getUrl, null)
            }
            parse(r)
        } catch (e: OfflineException) {
            throw e
        } catch (e: ConnectionProblem) {
            throw e
        } catch (e: SheetRefusedException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }
    }

    private fun parse(r: Response): JSONObject {
        if (r.code !in 200..299) throw ConnectionProblem("Google answered HTTP ${r.code} → ${httpHint(r.code)}")
        if (looksLikeSignIn(r)) {
            throw ConnectionProblem("Google asks for a sign-in → in Apps Script set the deployment's \"Who has access\" to \"Anyone\" (Всички)")
        }
        val json = try {
            JSONObject(r.body)
        } catch (e: JSONException) {
            if (r.body.contains("backend is running")) {
                throw ConnectionProblem("The script is an older version → paste the new Code.gs and deploy a New version")
            }
            if (r.body.contains("Script function not found")) {
                throw ConnectionProblem("The deployed script has no doPost/doGet → deploy a New version of the current Code.gs")
            }
            throw ConnectionProblem("Unexpected answer from Google (${r.contentType.ifBlank { "no type" }}) → check the URL")
        }
        if (!json.optBoolean("ok")) {
            val msg = json.optString("error", "Unknown error")
            if (msg == "Wrong secret key") throw ConnectionProblem("Wrong secret key → copy it again from the script's Execution log")
            if (msg == "Unknown action") throw ConnectionProblem("The script is older than the app → paste the new Code.gs and deploy a New version")
            throw SheetRefusedException(msg)
        }
        return json
    }

    /** One request with manual redirect handling (Apps Script answers with a 302 to googleusercontent). */
    private fun request(method: String, url: String, body: String?): Response {
        var conn = open(URL(url), method)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        var code = conn.responseCode
        var hops = 0
        while (code in 300..399 && hops < 6) {
            val location = conn.getHeaderField("Location") ?: break
            val next = URL(conn.url, location)
            conn.disconnect()
            conn = open(next, "GET") // the result of a POST is fetched with GET
            code = conn.responseCode
            hops++
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val res = Response(code, text, conn.url.host, conn.contentType ?: "")
        conn.disconnect()
        return res
    }

    private fun open(url: URL, method: String) = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = method
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 90_000 // a new month or year can take a while to create
        setRequestProperty("User-Agent", "BudgetApp/Android")
    }

    private fun looksLikeSignIn(r: Response) =
        r.host.startsWith("accounts.google.") || (r.contentType.startsWith("text/html") && r.body.contains("ServiceLogin"))

    private fun classify(e: Exception): Exception = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            OfflineException("No connection to Google")
        is SocketTimeoutException -> ConnectionProblem("The sheet took too long to answer → try again; if it repeats, check the script's Executions")
        is SSLException -> ConnectionProblem("Secure connection failed (${e.message}) → check the phone's date/time")
        else -> ConnectionProblem(describe(e))
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> "No connection to Google (is the phone online?)"
        is SocketTimeoutException -> "Timed out"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        private fun httpHint(code: Int) = when (code) {
            401, 403 -> "\"Who has access\" must be \"Anyone\" (Всички); deploy a New version after changing it"
            404 -> "the URL is wrong or the deployment was archived; copy the /exec URL from Manage deployments"
            405 -> "the request type was refused; deploy a New version of the current Code.gs"
            in 500..599 -> "Google had a server error; try again in a minute"
            else -> "check the URL"
        }

        /**
         * Accepts the full /exec URL, the URL without "https://", or just the Deployment ID.
         * Rejects the /dev test URL, which only works for the script owner in a browser.
         */
        fun normalizeUrl(raw: String): String {
            val s = raw.replace(Regex("\\s+"), "")
            if (s.isEmpty()) throw ConnectionProblem("The script URL is empty")
            if (Regex("^AKfy[\\w-]{20,}$").matches(s)) return "https://script.google.com/macros/s/$s/exec"
            val url = if (s.startsWith("http://") || s.startsWith("https://")) s.replaceFirst("http://", "https://") else "https://$s"
            val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
            if (clean.endsWith("/dev")) {
                throw ConnectionProblem("This is the test URL (/dev) → use the Web app URL ending in /exec from Manage deployments")
            }
            if (!clean.contains("script.google.com/") || !clean.endsWith("/exec")) {
                throw ConnectionProblem("The script URL should look like https://script.google.com/macros/s/…/exec")
            }
            return clean
        }
    }
}
