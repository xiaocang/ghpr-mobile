package com.ghpr.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.data.ChangedPr
import com.ghpr.app.data.GhprApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<ChangedPr> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class NotificationHistoryViewModel(private val apiClient: GhprApiClient) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiClient.api.sync(since = 0)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        items = response.body()?.changedPullRequests.orEmpty(),
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
}
