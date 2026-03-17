package com.ghpr.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.data.ChangedPr
import com.ghpr.app.data.DataStoreSyncCacheStore
import com.ghpr.app.data.GhprApiClient
import com.ghpr.app.data.toApiErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<ChangedPr> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class NotificationHistoryViewModel(
    private val apiClient: GhprApiClient,
    private val cacheStore: DataStoreSyncCacheStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state

    fun load() {
        viewModelScope.launch {
            val cached = cacheStore.readHistory()
            _state.value = _state.value.copy(
                items = if (cached.isNotEmpty()) cached else _state.value.items,
                isLoading = cached.isEmpty(),
                error = null,
            )
            try {
                val response = apiClient.api.sync(since = 0)
                if (response.isSuccessful) {
                    val latest = response.body()?.changedPullRequests.orEmpty()
                    cacheStore.writeHistory(latest)
                    _state.value = _state.value.copy(
                        items = latest,
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        error = response.toApiErrorMessage("Failed to load history"),
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
                val response = apiClient.api.sync(since = 0)
                if (response.isSuccessful) {
                    val latest = response.body()?.changedPullRequests.orEmpty()
                    cacheStore.writeHistory(latest)
                    _state.value = _state.value.copy(
                        items = latest,
                        isRefreshing = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        error = response.toApiErrorMessage("Failed to load history"),
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
}
