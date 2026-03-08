package com.ghpr.app.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.data.GhprApiClient
import com.ghpr.app.data.SubscribeRepoRequest
import com.ghpr.app.data.UnsubscribeRepoRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val subscriptions: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SubscriptionsViewModel(private val apiClient: GhprApiClient) : ViewModel() {

    private val _state = MutableStateFlow(SubscriptionsUiState())
    val state: StateFlow<SubscriptionsUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.api.listSubscriptions()
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        subscriptions = response.body()?.subscriptions.orEmpty(),
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        error = "Failed to load (${response.code()})",
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

    fun subscribe(repoFullName: String) {
        viewModelScope.launch {
            try {
                apiClient.api.subscribe(SubscribeRepoRequest(repoFullName))
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun unsubscribe(repoFullName: String) {
        viewModelScope.launch {
            try {
                apiClient.api.unsubscribe(UnsubscribeRepoRequest(repoFullName))
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }
}
