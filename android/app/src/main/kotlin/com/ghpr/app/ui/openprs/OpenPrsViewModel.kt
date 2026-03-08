package com.ghpr.app.ui.openprs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.auth.GitHubAuthState
import com.ghpr.app.auth.GitHubOAuthManager
import com.ghpr.app.data.DataStoreSyncCacheStore
import com.ghpr.app.data.GitHubGraphQLClient
import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.data.PrCategory
import com.ghpr.app.data.SsoAuthorizationRequired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OpenPrsUiState(
    val authoredPrs: List<OpenPullRequest> = emptyList(),
    val reviewRequestedPrs: List<OpenPullRequest> = emptyList(),
    val ssoRequired: List<SsoAuthorizationRequired> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val isSignedIn: Boolean = false,
)

class OpenPrsViewModel(
    private val gitHubOAuthManager: GitHubOAuthManager,
    private val gitHubGraphQLClient: GitHubGraphQLClient,
    private val cacheStore: DataStoreSyncCacheStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OpenPrsUiState())
    val state: StateFlow<OpenPrsUiState> = _state

    init {
        viewModelScope.launch {
            gitHubOAuthManager.authState.collect { authState ->
                _state.value = _state.value.copy(
                    isSignedIn = authState is GitHubAuthState.SignedIn,
                )
            }
        }
    }

    fun load() {
        if (gitHubOAuthManager.getToken() == null) {
            _state.value = _state.value.copy(isSignedIn = false)
            return
        }
        viewModelScope.launch {
            val cached = cacheStore.readOpenPrs()
            _state.value = _state.value.copy(
                authoredPrs = if (cached.isNotEmpty()) cached.filter { it.category == PrCategory.AUTHORED } else _state.value.authoredPrs,
                reviewRequestedPrs = if (cached.isNotEmpty()) cached.filter { it.category == PrCategory.REVIEW_REQUESTED } else _state.value.reviewRequestedPrs,
                isLoading = cached.isEmpty(),
                error = null,
                isSignedIn = true,
            )
            try {
                val repos = cacheStore.readSubscriptions()
                val result = gitHubGraphQLClient.fetchOpenPrs(repos)
                if (result.missingRepoScope) {
                    gitHubOAuthManager.signOut()
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Missing repo permissions. Please sign in again.",
                    )
                    return@launch
                }
                val prs = result.pullRequests.sortedByDescending { it.updatedAt }
                cacheStore.writeOpenPrs(prs)
                _state.value = _state.value.copy(
                    authoredPrs = prs.filter { it.category == PrCategory.AUTHORED },
                    reviewRequestedPrs = prs.filter { it.category == PrCategory.REVIEW_REQUESTED },
                    ssoRequired = result.ssoRequired,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error",
                    isLoading = false,
                )
            }
        }
    }

    fun refresh() {
        if (gitHubOAuthManager.getToken() == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            try {
                val repos = cacheStore.readSubscriptions()
                val result = gitHubGraphQLClient.fetchOpenPrs(repos)
                if (result.missingRepoScope) {
                    gitHubOAuthManager.signOut()
                    _state.value = _state.value.copy(
                        isRefreshing = false,
                        error = "Missing repo permissions. Please sign in again.",
                    )
                    return@launch
                }
                val prs = result.pullRequests.sortedByDescending { it.updatedAt }
                cacheStore.writeOpenPrs(prs)
                _state.value = _state.value.copy(
                    authoredPrs = prs.filter { it.category == PrCategory.AUTHORED },
                    reviewRequestedPrs = prs.filter { it.category == PrCategory.REVIEW_REQUESTED },
                    ssoRequired = result.ssoRequired,
                    isRefreshing = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error",
                    isRefreshing = false,
                )
            }
        }
    }
}
