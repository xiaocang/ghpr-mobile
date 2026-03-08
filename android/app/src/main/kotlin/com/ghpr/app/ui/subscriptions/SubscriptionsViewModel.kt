package com.ghpr.app.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.data.DataStoreSyncCacheStore
import com.ghpr.app.data.GhprApiClient
import com.ghpr.app.data.SubscribeRepoRequest
import com.ghpr.app.data.UnsubscribeRepoRequest
import com.ghpr.app.data.toApiErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val subscriptions: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class SubscriptionsViewModel(
    private val apiClient: GhprApiClient,
    private val cacheStore: DataStoreSyncCacheStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SubscriptionsUiState())
    val state: StateFlow<SubscriptionsUiState> = _state

    fun load() {
        viewModelScope.launch {
            val cached = cacheStore.readSubscriptions()
            _state.value = _state.value.copy(
                subscriptions = if (cached.isNotEmpty()) cached else _state.value.subscriptions,
                isLoading = cached.isEmpty(),
                error = null,
            )
            try {
                val response = apiClient.api.listSubscriptions()
                if (response.isSuccessful) {
                    val latest = response.body()?.subscriptions.orEmpty()
                    cacheStore.writeSubscriptions(latest)
                    _state.value = _state.value.copy(
                        subscriptions = latest,
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        error = response.toApiErrorMessage("Failed to load subscriptions"),
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error",
                    isLoading = false,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            try {
                val response = apiClient.api.listSubscriptions()
                if (response.isSuccessful) {
                    val latest = response.body()?.subscriptions.orEmpty()
                    cacheStore.writeSubscriptions(latest)
                    _state.value = _state.value.copy(
                        subscriptions = latest,
                        isRefreshing = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        error = response.toApiErrorMessage("Failed to load subscriptions"),
                        isRefreshing = false,
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error",
                    isRefreshing = false,
                )
            }
        }
    }

    fun subscribe(repoFullName: String) {
        viewModelScope.launch {
            try {
                val response = apiClient.api.subscribe(SubscribeRepoRequest(repoFullName))
                if (response.isSuccessful) {
                    load()
                } else {
                    _state.value = _state.value.copy(
                        error = response.toApiErrorMessage("Failed to subscribe"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun unsubscribe(repoFullName: String) {
        viewModelScope.launch {
            try {
                val response = apiClient.api.unsubscribe(UnsubscribeRepoRequest(repoFullName))
                if (response.isSuccessful) {
                    load()
                } else {
                    _state.value = _state.value.copy(
                        error = response.toApiErrorMessage("Failed to unsubscribe"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }
}
