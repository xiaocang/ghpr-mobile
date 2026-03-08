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
                } catch (_: Exception) {
                    throw GitHubAuthException("GitHub sign-in polling failed due to a network error.")
                }
            }

            val json = JSONObject(payload)
            if (json.has("access_token")) {
                onTokenReceived(json.getString("access_token"))
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

    private suspend fun onTokenReceived(token: String) {
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
            .apply()

        _authState.value = GitHubAuthState.SignedIn(login, avatarUrl)
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
    }
}

private class GitHubAuthException(message: String) : Exception(message)
