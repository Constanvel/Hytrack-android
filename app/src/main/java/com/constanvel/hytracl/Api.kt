package com.constanvel.hytracl

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The HyTrack Laravel JSON API (routes/api.php): the staff and management area of the app runs
 * on the same database, services and rules as the web dashboards. Signed in with a bearer token
 * from POST /api/masuk; the token lives for the app process, like [Demo]'s state, and signing in
 * again retires the old one. The patient side still runs on the device's demo data.
 */
object Api {
    var token: String? = null
        private set

    /** /api/akun "account": role, name, unit and the staff member's room (desk, doctor, support, pharmacy). */
    var account: JSONObject? = null
        private set
    val signedIn get() = token != null

    class Failure(val status: Int, message: String, val fields: Map<String, String> = emptyMap()) : Exception(message)

    /** A file sent with a form (support results): field name, file name, MIME type and bytes. */
    class Upload(val field: String, val name: String, val type: String, val bytes: ByteArray)

    /** Calls the API and returns the JSON body; a non-2xx answer throws [Failure] with Laravel's message. */
    fun call(method: String, path: String, body: JSONObject? = null, upload: Upload? = null, bearer: String? = token): JSONObject {
        // Mode prototype never reaches the network: its sample data answers instead.
        if (Prototype.active) return Prototype.call(method, path, body, upload)
        val connection = open(path) ?: throw Failure(0, OFFLINE)
        try {
            connection.requestMethod = method
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (upload != null) multipart(connection, body ?: JSONObject(), upload)
            else if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = text.takeIf { it.startsWith("{") }?.let(::JSONObject) ?: JSONObject()
            if (status in 200..299) return json
            if (status == 401) clear()
            throw failure(status, json)
        } catch (e: IOException) {
            throw Failure(0, OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads a protected file (a support result) into [into]; the server decides who may read it. */
    fun download(path: String, into: File): File {
        if (Prototype.active) return Prototype.download(path, into)
        val connection = open(path) ?: throw Failure(0, OFFLINE)
        try {
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw failure(status, text.takeIf { it.startsWith("{") }?.let(::JSONObject) ?: JSONObject())
            }
            connection.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
            return into
        } catch (e: IOException) {
            throw Failure(0, OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection? = try {
        (URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
        }
    } catch (e: IOException) {
        null
    }

    /** multipart/form-data: every JSON field as a part, then the file. */
    private fun multipart(connection: HttpURLConnection, fields: JSONObject, upload: Upload) {
        val boundary = "HyTrack" + UUID.randomUUID().toString().replace("-", "")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { out ->
            fun line(text: String = "") = out.write("$text\r\n".toByteArray())
            fields.keys().forEach { key ->
                if (fields.isNull(key)) return@forEach
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"$key\"")
                line()
                line(fields.get(key).toString())
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${upload.field}\"; filename=\"${upload.name.replace("\"", "")}\"")
            line("Content-Type: ${upload.type}")
            line()
            out.write(upload.bytes)
            line()
            line("--$boundary--")
        }
    }

    /** Laravel's 422 body: {"message": "...", "errors": {"field": ["..."]}}; first message per field. */
    fun failure(status: Int, json: JSONObject): Failure {
        val errors = json.optJSONObject("errors")
        val fields = errors?.keys()?.asSequence()?.associateWith { errors.getJSONArray(it).getString(0) }.orEmpty()
        val message = fields.values.firstOrNull() ?: json.optString("message").takeIf { it.isNotBlank() }
            ?: when (status) {
                401 -> "Sesi berakhir. Masuk kembali untuk melanjutkan."
                403 -> "Akun ini tidak memiliki akses ke halaman tersebut."
                404 -> "Data tidak ditemukan."
                429 -> "Terlalu banyak percobaan. Tunggu sebentar lalu coba lagi."
                else -> "Terjadi kesalahan. Coba lagi."
            }
        return Failure(status, message, fields)
    }

    /** Login · Petugas dan Manajemen: [role] is "staff" or "manager" and must match the account. */
    fun login(username: String, password: String, role: String): JSONObject {
        clear()
        val json = call("POST", "masuk", JSONObject().put("username", username).put("password", password).put("as", role), bearer = null)
        token = json.getString("token")
        return json.getJSONObject("account").also { account = it }
    }

    /** Retires the token on the server too; signing out works offline as well. */
    fun logout() {
        val current = token
        clear()
        if (current == null) return
        thread { runCatching { call("POST", "keluar", bearer = current) } }
    }

    /** Mode prototype's sample staff or manager account; there is no token behind it. */
    fun become(sample: JSONObject) {
        check(Prototype.active)
        clear()
        account = sample
    }

    private fun clear() {
        token = null
        account = null
    }

    fun query(vararg pairs: Pair<String, Any?>) = pairs.filter { it.second != null }
        .joinToString("&", prefix = "?") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), "UTF-8") }

    const val OFFLINE = "Tidak dapat terhubung ke server HyTrack. Periksa koneksi lalu coba lagi."
}

/** Runs [work] off the main thread and hands the outcome back on it, unless the screen is gone. */
fun <T> Activity.request(work: () -> T, done: (Result<T>) -> Unit) {
    thread {
        val result = runCatching(work)
        runOnUiThread { if (!isFinishing && !isDestroyed) done(result) }
    }
}

fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)

fun JSONObject.list(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
