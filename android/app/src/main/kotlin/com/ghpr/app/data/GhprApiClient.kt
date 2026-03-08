package com.ghpr.app.data

import com.ghpr.app.auth.FirebaseAuthManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
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

    @DELETE("devices/register")
    suspend fun unregisterDevice(@Body body: UnregisterDeviceRequest): Response<ApiResult>

    @GET("devices")
    suspend fun listDevices(): Response<DevicesResponse>

    @POST("subscriptions")
    suspend fun subscribe(@Body body: SubscribeRepoRequest): Response<ApiResult>

    @DELETE("subscriptions")
    suspend fun unsubscribe(@Body body: UnsubscribeRepoRequest): Response<ApiResult>

    @GET("subscriptions")
    suspend fun listSubscriptions(): Response<SubscriptionsResponse>

    @GET("mobile/sync")
    suspend fun sync(
        @Query("since") since: Long,
        @Query("cursorDeliveryId") cursorDeliveryId: String = "",
        @Query("limit") limit: Int = 100,
    ): Response<SyncResponse>
}

class GhprApiClient(
    baseUrl: String,
    private val authManager: FirebaseAuthManager,
) {
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
        val token = kotlinx.coroutines.runBlocking { authManager.getIdToken() }
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }
}
