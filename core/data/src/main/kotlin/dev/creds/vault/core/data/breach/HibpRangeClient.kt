package dev.creds.vault.core.data.breach

import dev.creds.vault.core.domain.breach.BreachRangeSource
import dev.creds.vault.core.domain.breach.HibpRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only network client in the app: Have I Been Pwned's password range API.
 *
 * It is used only when the user has switched the online check on, and the HTTP client is
 * not even constructed until then. Each request carries five hex characters of a SHA-1
 * and asks for a padded response, so neither the request nor the size of the reply says
 * anything about the password. No cookies, no cache, and nothing identifying beyond the
 * User-Agent the API requires.
 */
@Singleton
class HibpRangeClient @Inject constructor() : BreachRangeSource {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .cache(null)
            .build()
    }

    override suspend fun range(prefix: String): String {
        require(HibpRange.isValidPrefix(prefix)) { "Not a five-character uppercase hex prefix" }

        val request = Request.Builder()
            .url("$BASE_URL$prefix")
            .header("Add-Padding", "true")
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Have I Been Pwned returned HTTP ${response.code}")
                response.body.string()
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.pwnedpasswords.com/range/"
        const val USER_AGENT = "Creds-Android"
    }
}
