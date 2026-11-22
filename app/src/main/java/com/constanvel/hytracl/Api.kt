package com.constanvel.hytracl

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The Laravel JSON API (routes/api.php): same database and rules as the web app. Signed in
 * with a bearer token from POST /api/masuk. The token lives for the app process, like
 * [Demo]'s state; signing in again issues a new one and retires the old.
 */
object Api {
    /** Set when signed in against the server. Without it the app runs on the offline demo. */
    var token: String? = null
        private set
    var role: String? = null
        private set
    val online get() = token != null

    class Failure(val status: Int, message: String, val fields: Map<String, String> = emptyMap()) : Exception(message)

    /** Calls the API and returns the JSON body; non-2xx answers throw [Failure] with Laravel's messages. */
    fun call(method: String, path: String, body: JSONObject? = null, bearer: String? = token): JSONObject {
        val connection = try {
            URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure(0, OFFLINE)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = text.takeIf { it.startsWith("{") }?.let(::JSONObject) ?: JSONObject()
            if (status in 200..299) return json
            if (status == 401) token = null
            throw failure(status, json)
        } catch (e: IOException) {
            throw Failure(0, OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    /** Laravel's 422 body: {"message": "...", "errors": {"field": ["..."]}}; first message per field. */
    fun failure(status: Int, json: JSONObject): Failure {
        val errors = json.optJSONObject("errors")
        val fields = errors?.keys()?.asSequence()?.associateWith { errors.getJSONArray(it).getString(0) }.orEmpty()
        val message = fields.values.firstOrNull() ?: json.optString("message").takeIf { it.isNotBlank() }
            ?: if (status == 404) "Data tidak ditemukan." else "Terjadi kesalahan. Coba lagi."
        return Failure(status, message, fields)
    }

    fun login(username: String, password: String): JSONObject {
        token = null
        return signIn(call("POST", "masuk", JSONObject().put("username", username).put("password", password)))
    }

    /** Sign-up step 2/3: the right code creates the account and signs it in. */
    fun verifySignup(signup: String, code: String): JSONObject {
        token = null
        return signIn(call("POST", "daftar/verifikasi", JSONObject().put("signup", signup).put("code", code)))
    }

    private fun signIn(json: JSONObject): JSONObject {
        token = json.getString("token")
        val account = json.getJSONObject("account")
        role = account.getString("role")
        return account
    }

    /** Retires the token on the server too; signing out works offline as well. */
    fun logout() {
        val current = token ?: return
        token = null
        role = null
        thread { runCatching { call("POST", "keluar", bearer = current) } }
    }

    const val OFFLINE = "Tidak dapat terhubung ke server HyTrack. Periksa koneksi lalu coba lagi."
}

/** Runs [work] off the main thread and hands the outcome back on it, unless the screen is gone. */
fun <T> Activity.request(work: () -> T, done: (Result<T>) -> Unit) {
    thread {
        val result = runCatching(work)
        runOnUiThread { if (!isFinishing && !isDestroyed) done(result) }
    }
}

fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
