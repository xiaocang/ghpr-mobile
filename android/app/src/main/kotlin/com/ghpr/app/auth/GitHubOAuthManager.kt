package com.ghpr.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ghpr.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

sealed interface GitHubAuthState {
    data object SignedOut : GitHubAuthState
    data class Authenticating(
        val userCode: String,
        val verificationUri: String,
        val pollingError: String? = null,
    ) : GitHubAuthState
    data class Error(
        val message: String,
    ) : GitHubAuthState
    data class SignedIn(
        val login: String,
        val avatarUrl: String,
    ) : GitHubAuthState
}

class GitHubOAuthManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "github_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val client = OkHttpClient()

    private val _authState = MutableStateFlow<GitHubAuthState>(loadInitialState())
    val authState: StateFlow<GitHubAuthState> = _authState

    private data class DeviceFlowInfo(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    private data class TokenPayload(
        val accessToken: String,
        val tokenType: String?,
        val expiresAtMillis: Long?,
        val refreshToken: String?,
        val refreshTokenExpiresAtMillis: Long?,
        val source: String,
    )

    private fun loadInitialState(): GitHubAuthState {
        val token = prefs.getString(KEY_TOKEN, null)
        val login = prefs.getString(KEY_LOGIN, null)
        val avatarUrl = prefs.getString(KEY_AVATAR_URL, null)
        return if (token != null && login != null) {
            GitHubAuthState.SignedIn(login, avatarUrl.orEmpty())
        } else {
            GitHubAuthState.SignedOut
        }
    }

    suspend fun startDeviceFlow() {
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        if (clientId.isBlank()) {
            _authState.value = GitHubAuthState.Error(
                "GitHub client ID is not configured. Add github.clientId to android/local.properties.",
            )
            return
        }

        try {
            val flowInfo = requestDeviceCode(clientId)
            _authState.value = GitHubAuthState.Authenticating(
                userCode = flowInfo.userCode,
                verificationUri = flowInfo.verificationUri,
            )
            pollForToken(
                clientId = clientId,
                deviceCode = flowInfo.deviceCode,
                intervalSeconds = flowInfo.intervalSeconds,
                expiresInSeconds = flowInfo.expiresInSeconds,
            )
        } catch (e: GitHubAuthException) {
            _authState.value = GitHubAuthState.Error(e.message ?: "GitHub sign-in failed.")
        } catch (_: Exception) {
            _authState.value = GitHubAuthState.Error("GitHub sign-in failed due to a network error.")
        }
    }

    private suspend fun requestDeviceCode(clientId: String): DeviceFlowInfo {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "repo read:user")
            .build()

        val request = Request.Builder()
            .url("https://github.com/login/device/code")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        val payload = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GitHubAuthException("Could not start GitHub sign-in. HTTP ${response.code}.")
                }
                raw
            }
        }

        val json = JSONObject(payload)
        if (json.has("error")) {
            throw GitHubAuthException("Could not start GitHub sign-in: ${json.optString("error")}.")
        }

        return DeviceFlowInfo(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
            expiresInSeconds = json.optInt("expires_in", 900).coerceAtLeast(1),
        )
    }

    private suspend fun pollForToken(
        clientId: String,
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
    ) {
        var pollingIntervalSeconds = intervalSeconds
        val expiresAtMillis = System.currentTimeMillis() + (expiresInSeconds * 1000L)

        while (_authState.value is GitHubAuthState.Authenticating) {
            if (System.currentTimeMillis() > expiresAtMillis) {
                throw GitHubAuthException("GitHub authorization timed out. Please retry.")
            }

            delay(pollingIntervalSeconds * 1000L)

            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val request = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            val payload = withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw GitHubAuthException("GitHub sign-in polling failed. HTTP ${response.code}.")
                        }
                        raw
                    }
                } catch (e: GitHubAuthException) {
                    throw e
                } catch (e: Exception) {
                    val current = _authState.value
                    if (current is GitHubAuthState.Authenticating) {
                        _authState.value = current.copy(
                            pollingError = e.message ?: "Network error",
                        )
                    }
                    continue
                }
            }

            // Clear polling error on successful response
            val current = _authState.value
            if (current is GitHubAuthState.Authenticating && current.pollingError != null) {
                _authState.value = current.copy(pollingError = null)
            }

            val json = JSONObject(payload)
            if (json.has("access_token")) {
                val tokenPayload = parseTokenPayload(json, source = "device_flow")
                    ?: throw GitHubAuthException("GitHub sign-in returned an invalid token payload.")
                onTokenReceived(tokenPayload)
                return
            }

            when (json.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    pollingIntervalSeconds += 5
                    continue
                }
                "expired_token" -> throw GitHubAuthException("GitHub authorization code expired. Please retry.")
                "access_denied" -> throw GitHubAuthException("GitHub authorization was denied.")
                "" -> throw GitHubAuthException("GitHub sign-in polling returned an invalid response.")
                else -> throw GitHubAuthException("GitHub sign-in failed: ${json.optString("error")}.")
            }
        }
    }

    private suspend fun onTokenReceived(tokenPayload: TokenPayload) {
        val token = tokenPayload.accessToken
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .build()

        val payload = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GitHubAuthException("Signed in, but failed to fetch GitHub user profile. HTTP ${response.code}.")
                }
                raw
            }
        }

        val json = JSONObject(payload)
        if (!json.has("login")) {
            throw GitHubAuthException("GitHub user profile response was invalid.")
        }
        val login = json.getString("login")
        val avatarUrl = json.optString("avatar_url", "")

        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_LOGIN, login)
            .putString(KEY_AVATAR_URL, avatarUrl)
            .putLong(KEY_TOKEN_EXPIRES_AT, tokenPayload.expiresAtMillis ?: 0L)
            .putString(KEY_REFRESH_TOKEN, tokenPayload.refreshToken)
            .putLong(KEY_REFRESH_TOKEN_EXPIRES_AT, tokenPayload.refreshTokenExpiresAtMillis ?: 0L)
            .putString(KEY_TOKEN_TYPE, tokenPayload.tokenType.orEmpty())
            .putString(KEY_TOKEN_SOURCE, tokenPayload.source)
            .apply()

        _authState.value = GitHubAuthState.SignedIn(login, avatarUrl)
    }

    private fun parseTokenPayload(json: JSONObject, source: String): TokenPayload? {
        val accessToken = json.optString("access_token", "").trim()
        if (accessToken.isBlank()) return null

        val expiresInSeconds = json.optLong("expires_in", 0L)
        val refreshTokenExpiresInSeconds = json.optLong("refresh_token_expires_in", 0L)
        val nowMillis = System.currentTimeMillis()

        return TokenPayload(
            accessToken = accessToken,
            tokenType = json.optString("token_type").ifBlank { null },
            expiresAtMillis = expiresInSeconds.takeIf { it > 0L }?.let { nowMillis + it * 1000L },
            refreshToken = json.optString("refresh_token").ifBlank { null },
            refreshTokenExpiresAtMillis = refreshTokenExpiresInSeconds.takeIf { it > 0L }?.let {
                nowMillis + it * 1000L
            },
            source = source,
        )
    }

    suspend fun getTokenForServerSync(): String? {
        val current = getToken() ?: return null
        val expiresAtMillis = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L).takeIf { it > 0L }
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        if (expiresAtMillis == null) return current

        val shouldRefresh = System.currentTimeMillis() >= (expiresAtMillis - 5 * 60 * 1000L)
        if (!shouldRefresh || refreshToken.isNullOrBlank()) return current

        val refreshed = refreshAccessToken(refreshToken) ?: return current
        return try {
            onTokenReceived(refreshed)
            refreshed.accessToken
        } catch (_: Exception) {
            current
        }
    }

    private suspend fun refreshAccessToken(refreshToken: String): TokenPayload? {
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        if (clientId.isBlank()) return null

        val bodyBuilder = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
        val clientSecret = BuildConfig.GITHUB_CLIENT_SECRET
        if (clientSecret.isNotBlank()) {
            bodyBuilder.add("client_secret", clientSecret)
        }

        val request = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .addHeader("Accept", "application/json")
            .post(bodyBuilder.build())
            .build()

        val payload = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string().orEmpty()
                }
            }.getOrNull()
        } ?: return null

        val json = JSONObject(payload)
        if (json.has("error")) return null
        return parseTokenPayload(json, source = "refresh_token")
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getLogin(): String? = prefs.getString(KEY_LOGIN, null)

    fun signOut() {
        prefs.edit().clear().apply()
        _authState.value = GitHubAuthState.SignedOut
    }

    companion object {
        private const val KEY_TOKEN = "github_token"
        private const val KEY_LOGIN = "github_login"
        private const val KEY_AVATAR_URL = "github_avatar_url"
        private const val KEY_TOKEN_EXPIRES_AT = "github_token_expires_at"
        private const val KEY_REFRESH_TOKEN = "github_refresh_token"
        private const val KEY_REFRESH_TOKEN_EXPIRES_AT = "github_refresh_token_expires_at"
        private const val KEY_TOKEN_TYPE = "github_token_type"
        private const val KEY_TOKEN_SOURCE = "github_token_source"
    }
}

private class GitHubAuthException(message: String) : Exception(message)
