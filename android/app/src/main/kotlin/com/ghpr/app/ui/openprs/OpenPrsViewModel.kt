package com.ghpr.app.ui.openprs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.auth.GitHubAuthState
import com.ghpr.app.auth.GitHubOAuthManager
import com.ghpr.app.data.DataStoreSyncCacheStore
import com.ghpr.app.data.GhprApiClient
import com.ghpr.app.data.GitHubGraphQLClient
import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.data.PrCategory
import com.ghpr.app.data.RetryFlakyJob
import com.ghpr.app.data.RetryFlakyRequest
import com.ghpr.app.data.SsoAuthorizationRequired
import com.ghpr.app.data.toApiErrorMessage
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
    val retryFlakyMessage: String? = null,
    /** Active/recent retry-flaky jobs keyed by "owner/repo#number" */
    val retryFlakyJobs: Map<String, RetryFlakyJob> = emptyMap(),
    /** PR keys currently submitting a retry-flaky request */
    val retryFlakySubmitting: Set<String> = emptySet(),
    /** PR keys currently submitting a retry-ci request */
    val retryCiSubmitting: Set<String> = emptySet(),
    /** Currently expanded PR key (null = none expanded) */
    val expandedPrKey: String? = null,
)

class OpenPrsViewModel(
    private val gitHubOAuthManager: GitHubOAuthManager,
    private val gitHubGraphQLClient: GitHubGraphQLClient,
    private val cacheStore: DataStoreSyncCacheStore,
    private val apiClient: GhprApiClient,
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
        if (gitHubOAuthManager.getToken() == null) return
        viewModelScope.launch {
            val cached = runCatching { cacheStore.readOpenPrs() }.getOrDefault(emptyList())
            val (authored, reviewRequested) = cached.partition { it.category == PrCategory.AUTHORED }
            _state.value = _state.value.copy(
                authoredPrs = authored.ifEmpty { _state.value.authoredPrs },
                reviewRequestedPrs = reviewRequested.ifEmpty { _state.value.reviewRequestedPrs },
                isLoading = cached.isEmpty(),
                error = null,
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
                val (authored, reviewRequested) = prs.partition { it.category == PrCategory.AUTHORED }
                _state.value = _state.value.copy(
                    authoredPrs = authored,
                    reviewRequestedPrs = reviewRequested,
                    ssoRequired = result.ssoRequired,
                    isLoading = false,
                )
                loadRetryJobs()
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
                val (authored, reviewRequested) = prs.partition { it.category == PrCategory.AUTHORED }
                _state.value = _state.value.copy(
                    authoredPrs = authored,
                    reviewRequestedPrs = reviewRequested,
                    ssoRequired = result.ssoRequired,
                    isRefreshing = false,
                )
                loadRetryJobs()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Unknown error",
                    isRefreshing = false,
                )
            }
        }
    }

    fun retryCi(pr: OpenPullRequest) {
        val key = prKey(pr)
        if (_state.value.retryCiSubmitting.contains(key)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                retryCiSubmitting = _state.value.retryCiSubmitting + key,
            )
            try {
                val repoFullName = "${pr.repoOwner}/${pr.repoName}"
                val response = apiClient.api.retryCi(
                    RetryFlakyRequest(repoFullName = repoFullName, prNumber = pr.number),
                )
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        retryFlakyMessage = "CI retry queued for ${pr.repoName}#${pr.number}",
                    )
                } else {
                    _state.value = _state.value.copy(
                        retryFlakyMessage = response.toApiErrorMessage("CI retry failed"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    retryFlakyMessage = "CI retry failed: ${e.message}",
                )
            } finally {
                _state.value = _state.value.copy(
                    retryCiSubmitting = _state.value.retryCiSubmitting - key,
                )
            }
        }
    }

    fun retryFlaky(pr: OpenPullRequest) {
        val key = prKey(pr)
        if (_state.value.retryFlakySubmitting.contains(key)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                retryFlakySubmitting = _state.value.retryFlakySubmitting + key,
            )
            try {
                val repoFullName = "${pr.repoOwner}/${pr.repoName}"
                val response = apiClient.api.retryFlaky(
                    RetryFlakyRequest(repoFullName = repoFullName, prNumber = pr.number),
                )
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        retryFlakyMessage = "Retry queued for ${pr.repoName}#${pr.number}",
                    )
                    loadRetryJobs()
                } else {
                    _state.value = _state.value.copy(
                        retryFlakyMessage = response.toApiErrorMessage("Retry failed"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    retryFlakyMessage = "Retry failed: ${e.message}",
                )
            } finally {
                _state.value = _state.value.copy(
                    retryFlakySubmitting = _state.value.retryFlakySubmitting - key,
                )
            }
        }
    }

    fun cancelRetryFlaky(pr: OpenPullRequest) {
        viewModelScope.launch {
            try {
                val repoFullName = "${pr.repoOwner}/${pr.repoName}"
                val response = apiClient.api.cancelRetryFlaky(
                    RetryFlakyRequest(repoFullName = repoFullName, prNumber = pr.number),
                )
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        retryFlakyMessage = "Retry cancelled for ${pr.repoName}#${pr.number}",
                    )
                    loadRetryJobs()
                } else {
                    _state.value = _state.value.copy(
                        retryFlakyMessage = response.toApiErrorMessage("Cancel failed"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    retryFlakyMessage = "Cancel failed: ${e.message}",
                )
            }
        }
    }

    fun loadRetryJobs() {
        viewModelScope.launch {
            try {
                val response = apiClient.api.listRetryFlakyJobs()
                if (response.isSuccessful) {
                    val jobs = response.body()?.jobs.orEmpty()
                    _state.value = _state.value.copy(
                        retryFlakyJobs = jobs.associateBy { "${it.repoFullName}#${it.prNumber}" },
                    )
                }
            } catch (_: Exception) {
                // Silently ignore — retry job status is best-effort
            }
        }
    }

    fun toggleExpanded(pr: OpenPullRequest) {
        val key = prKey(pr)
        _state.value = _state.value.copy(
            expandedPrKey = if (_state.value.expandedPrKey == key) null else key,
        )
    }

    fun clearRetryFlakyMessage() {
        _state.value = _state.value.copy(retryFlakyMessage = null)
    }

    companion object {
        fun prKey(pr: OpenPullRequest): String = "${pr.repoOwner}/${pr.repoName}#${pr.number}"
    }
}
