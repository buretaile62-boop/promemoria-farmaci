package it.promemoriafarmaci.anziano.data

import it.promemoriafarmaci.anziano.BuildConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, body: String) : IOException("HTTP $code: $body")

/**
 * Client minimo per Supabase (Auth + PostgREST RPC).
 * L'anziano usa un accesso anonimo legato al telefono: nessuna email o password da ricordare.
 */
class SupabaseApi(private val store: Store) {
    private val url = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_ANON_KEY
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    val isConfigured get() = url.startsWith("https://") && key.length > 20

    private fun post(path: String, body: String, bearer: String? = null): String {
        // Le chiavi "sb_publishable_…" vanno solo in "apikey"; Authorization porta il token utente.
        val builder = Request.Builder()
            .url("$url$path")
            .header("apikey", key)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val request = builder.post(body.toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, text)
            return text
        }
    }

    private fun saveSession(text: String): Session {
        val obj = Store.json.parseToJsonElement(text).jsonObject
        val session = Session(
            accessToken = obj["access_token"]!!.jsonPrimitive.content,
            refreshToken = obj["refresh_token"]!!.jsonPrimitive.content,
            expiresAtMillis = System.currentTimeMillis() + obj["expires_in"]!!.jsonPrimitive.long * 1000,
            userId = obj["user"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
        store.session = session
        return session
    }

    /** Restituisce un token valido, creando l'account anonimo o rinnovando la sessione. */
    @Synchronized
    fun token(): String {
        val current = store.session
        if (current == null) {
            return saveSession(post("/auth/v1/signup", "{}")).accessToken
        }
        if (current.expiresAtMillis - 120_000 > System.currentTimeMillis()) return current.accessToken
        val body = buildJsonObject { put("refresh_token", current.refreshToken) }.toString()
        return saveSession(post("/auth/v1/token?grant_type=refresh_token", body)).accessToken
    }

    fun rpc(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val text = post("/rest/v1/rpc/$name", args.toString(), token())
        return if (text.isBlank()) null else Store.json.parseToJsonElement(text)
    }
}
