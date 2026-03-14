package com.ghpr.app.data

import android.util.Log
import com.ghpr.app.auth.FirebaseAuthManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Query

data class ApiResult(val ok: Boolean? = null, val error: String? = null)

data class RegisterDeviceRequest(val token: String, val platform: String = "android")
data class SubscribeRepoRequest(val repoFullName: String)
data class UnsubscribeRepoRequest(val repoFullName: String)
data class UnregisterDeviceRequest(val token: String)
data class DeviceInfo(val platform: String, val tokenPreview: String)
data class DevicesResponse(val ok: Boolean, val devices: List<DeviceInfo>)

data class SubscriptionsResponse(val ok: Boolean, val subscriptions: List<String>)
data class RunnerStatusResponse(
    val ok: Boolean,
    val deviceId: String? = null,
    val githubLogin: String? = null,
    val lastPollStatus: String? = null,
    val lastPollError: String? = null,
    val lastPollAt: String? = null,
    val lastSeenAt: String? = null,
)

data class ChangedPr(
    val repo: String,
    val number: Int,
    val action: String,
    val changedAtMs: Long,
)

data class SyncResponse(
    val ok: Boolean,
    val nextSince: Long,
    val nextCursorDeliveryId: String,
    val hasMore: Boolean,
    val changedPullRequests: List<ChangedPr>,
)

interface GhprApi {
    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<ApiResult>

    @HTTP(method = "DELETE", path = "devices/register", hasBody = true)
    suspend fun unregisterDevice(@Body body: UnregisterDeviceRequest): Response<ApiResult>

    @GET("devices")
    suspend fun listDevices(): Response<DevicesResponse>

    @POST("subscriptions")
    suspend fun subscribe(@Body body: SubscribeRepoRequest): Response<ApiResult>

    @HTTP(method = "DELETE", path = "subscriptions", hasBody = true)
    suspend fun unsubscribe(@Body body: UnsubscribeRepoRequest): Response<ApiResult>

    @GET("subscriptions")
    suspend fun listSubscriptions(): Response<SubscriptionsResponse>

    @GET("mobile/sync")
    suspend fun sync(
        @Query("since") since: Long,
        @Query("cursorDeliveryId") cursorDeliveryId: String = "",
        @Query("limit") limit: Int = 100,
    ): Response<SyncResponse>

    @GET("runners/poll-info")
    suspend fun runnerStatus(): Response<RunnerStatusResponse>
}

class GhprApiClient(
    baseUrl: String,
    private val authManager: FirebaseAuthManager,
) {
    companion object {
        private const val TAG = "GhprApiClient"
    }

    private val okhttp = OkHttpClient.Builder()
        .addInterceptor(authInterceptor())
        .build()

    val api: GhprApi = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(okhttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GhprApi::class.java)

    private fun authInterceptor() = Interceptor { chain ->
        val token = kotlinx.coroutines.runBlocking {
            runCatching { authManager.ensureIdToken() }
                .onFailure { Log.e(TAG, "Failed to obtain Firebase ID token", it) }
                .getOrNull()
        }

        val requestWithToken = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) {
                header("Authorization", "Bearer $token")
            }
        }.build()

        val response = chain.proceed(requestWithToken)
        if (response.code != 401) {
            return@Interceptor response
        }

        val refreshedToken = kotlinx.coroutines.runBlocking {
            runCatching { authManager.ensureIdToken(forceRefresh = true) }
                .onFailure { Log.e(TAG, "Failed to refresh Firebase ID token after 401", it) }
                .getOrNull()
        }

        if (refreshedToken.isNullOrBlank()) {
            return@Interceptor response
        }

        response.close()
        val retryRequest = chain.request().newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .build()
        chain.proceed(retryRequest)
    }
}

fun <T> Response<T>.toApiErrorMessage(fallbackPrefix: String): String {
    if (code() == 401) {
        return "Unauthorized (401): Firebase anonymous sign-in or Bearer token is invalid."
    }

    val serverError = try {
        errorBody()?.string()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { JSONObject(raw).optString("error") }
                    .getOrDefault("")
                    .trim()
                    .ifBlank { null }
            }
    } catch (_: Exception) {
        null
    }

    return if (serverError != null) {
        "$fallbackPrefix (${code()}): $serverError"
    } else {
        "$fallbackPrefix (${code()})"
    }
}
