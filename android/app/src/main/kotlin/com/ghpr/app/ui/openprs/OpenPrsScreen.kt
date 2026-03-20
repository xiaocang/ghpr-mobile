package com.ghpr.app.ui.openprs

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ghpr.app.data.CIWorkflowInfo
import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.data.RetryFlakyJob
import com.ghpr.app.data.SsoAuthorizationRequired
import com.ghpr.app.ui.components.EmptyStateView
import com.ghpr.app.ui.components.ErrorStateView
import com.ghpr.app.ui.components.StatusBadge
import com.ghpr.app.ui.theme.LocalGhprStatusColors
import com.ghpr.app.ui.theme.NeoButton
import com.ghpr.app.ui.theme.MonoStyle
import com.ghpr.app.ui.theme.NeoCard
import com.ghpr.app.ui.theme.neoTopBarBorder
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenPrsScreen(viewModel: OpenPrsViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.retryFlakyMessage) {
        state.retryFlakyMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearRetryFlakyMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open PRs") },
                modifier = Modifier.neoTopBarBorder(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.error != null && !state.isSignedIn -> {
                    ErrorStateView(
                        message = state.error!!,
                    )
                }
                !state.isSignedIn -> {
                    EmptyStateView(
                        icon = Icons.Default.AccountCircle,
                        title = "Sign in to view PRs",
                        subtitle = "Go to Settings and sign in with GitHub to see open pull requests",
                    )
                }
                state.isLoading && !state.isRefreshing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.error != null && !state.isRefreshing -> {
                    ErrorStateView(
                        message = state.error!!,
                        onRetry = { viewModel.load() },
                    )
                }
                state.authoredPrs.isEmpty() && state.reviewRequestedPrs.isEmpty() && !state.isLoading -> {
                    if (state.ssoRequired.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { SsoBanner(state.ssoRequired) }
                        }
                    } else {
                        EmptyStateView(
                            icon = Icons.Default.Info,
                            title = "No open PRs",
                            subtitle = "Subscribe to repos in the Subs tab to see your PRs and review requests here",
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (state.ssoRequired.isNotEmpty()) {
                            item { SsoBanner(state.ssoRequired) }
                        }
                        if (state.authoredPrs.isNotEmpty()) {
                            item { SectionHeader("My PRs", state.authoredPrs.size) }
                            items(
                                state.authoredPrs,
                                key = { OpenPrsViewModel.prKey(it) },
                            ) { pr ->
                                val key = OpenPrsViewModel.prKey(pr)
                                val job = state.retryFlakyJobs[key]
                                val isRetrySubmitting = state.retryFlakySubmitting.contains(key)
                                val isCiSubmitting = state.retryCiSubmitting.contains(key)
                                val hasActiveJob = job != null && job.status == "active"
                                val isCiFailure = pr.ciState?.uppercase() in listOf("FAILURE", "ERROR")
                                val isExpanded = state.expandedPrKey == key
                                val swipeEnabled = isCiFailure && !isRetrySubmitting && !isCiSubmitting && !hasActiveJob && !isExpanded
                                SwipeRevealBox(
                                    enabled = swipeEnabled,
                                    onRetryCi = { viewModel.retryCi(pr) },
                                    onRetryFlaky = { viewModel.retryFlaky(pr) },
                                ) {
                                    Column {
                                        OpenPrCard(
                                            pr = pr,
                                            showReviewMetrics = true,
                                            isExpanded = isExpanded,
                                            onToggleExpand = { viewModel.toggleExpanded(pr) },
                                            retryFlakyJob = job,
                                            isRetrySubmitting = isRetrySubmitting,
                                        )
                                        AnimatedVisibility(
                                            visible = isExpanded,
                                            enter = expandVertically(),
                                            exit = shrinkVertically(),
                                        ) {
                                            PrExpandedDetail(
                                                pr = pr,
                                                retryFlakyJob = job,
                                                isRetrySubmitting = isRetrySubmitting,
                                                isCiSubmitting = isCiSubmitting,
                                                onRetryCi = { viewModel.retryCi(pr) },
                                                onRetryFlaky = { viewModel.retryFlaky(pr) },
                                                onCancelRetryFlaky = { viewModel.cancelRetryFlaky(pr) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (state.reviewRequestedPrs.isNotEmpty()) {
                            item { SectionHeader("Review Requested", state.reviewRequestedPrs.size) }
                            items(
                                state.reviewRequestedPrs,
                                key = { OpenPrsViewModel.prKey(it) },
                            ) { pr ->
                                val key = OpenPrsViewModel.prKey(pr)
                                val isExpanded = state.expandedPrKey == key
                                Column {
                                    OpenPrCard(
                                        pr = pr,
                                        showReviewMetrics = false,
                                        isExpanded = isExpanded,
                                        onToggleExpand = { viewModel.toggleExpanded(pr) },
                                    )
                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = expandVertically(),
                                        exit = shrinkVertically(),
                                    ) {
                                        PrExpandedDetail(
                                            pr = pr,
                                            retryFlakyJob = null,
                                            isRetrySubmitting = false,
                                            isCiSubmitting = false,
                                            onRetryCi = null,
                                            onRetryFlaky = null,
                                            onCancelRetryFlaky = null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SsoBanner(ssoRequired: List<SsoAuthorizationRequired>) {
    val context = LocalContext.current
    val orgNames = ssoRequired.joinToString(", ") { it.orgName }

    NeoCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "SSO authorization required",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = "Your token needs SSO authorization for: $orgNames",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ssoRequired.forEach { sso ->
                    NeoButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, sso.authUrl.toUri()),
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            text = "Authorize ${sso.orgName}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeRevealBox(
    enabled: Boolean,
    onRetryCi: () -> Unit,
    onRetryFlaky: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val revealWidthDp = 140.dp
    val density = LocalDensity.current
    val revealWidthPx = with(density) { revealWidthDp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp)),
    ) {
        // Background action buttons — right-aligned, revealed when card slides left
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Single retry (blue)
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF3B82F6))
                    .clickable {
                        scope.launch {
                            offsetX.animateTo(0f)
                        }
                        onRetryCi()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\uD83D\uDD04",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Retry flaky x3 (orange)
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF59E0B))
                    .clickable {
                        scope.launch {
                            offsetX.animateTo(0f)
                        }
                        onRetryFlaky()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\uD83D\uDD04x3",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // Foreground card — slides left
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                // Snap: if past halfway, open; otherwise close
                                if (offsetX.value < -revealWidthPx / 2) {
                                    offsetX.animateTo(-revealWidthPx)
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount)
                                    .coerceIn(-revealWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun OpenPrCard(
    pr: OpenPullRequest,
    showReviewMetrics: Boolean,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    retryFlakyJob: RetryFlakyJob? = null,
    isRetrySubmitting: Boolean = false,
) {
    val statusColors = LocalGhprStatusColors.current
    val hasActiveJob = retryFlakyJob != null && retryFlakyJob.status == "active"

    NeoCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onToggleExpand?.invoke() },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = pr.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pr.repoOwner}/${pr.repoName}#${pr.number}",
                    style = MonoStyle.codeSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pr.isDraft) {
                        StatusBadge(
                            text = "Draft",
                            color = statusColors.pending,
                        )
                    }
                    pr.ciState?.let { ci ->
                        val ciColor = when (ci.uppercase()) {
                            "SUCCESS" -> statusColors.merged
                            "FAILURE", "ERROR" -> statusColors.closed
                            else -> statusColors.pending
                        }
                        // Show compact workflow count format
                        val ciText = ciStatusText(pr)
                        StatusBadge(text = ciText, color = ciColor)
                        if (pr.ciIsRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(start = 2.dp)
                                    .size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        if (!isExpanded && ci.uppercase() in listOf("FAILURE", "ERROR")) {
                            when {
                                isRetrySubmitting -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(start = 2.dp)
                                            .height(20.dp)
                                            .width(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                hasActiveJob -> {
                                    StatusBadge(
                                        text = "${retryFlakyJob!!.retriesRemaining}/${retryFlakyJob.totalRetries}",
                                        color = statusColors.pending,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                text = pr.authorLogin,
                style = MonoStyle.codeSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (showReviewMetrics) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusBadge(
                        text = "approved ${pr.approvalCount}",
                        color = statusColors.success,
                    )
                    StatusBadge(
                        text = "unresolved ${pr.unresolvedCount}",
                        color = statusColors.pending,
                    )
                }
            }
        }
    }
}

internal fun ciStatusText(pr: OpenPullRequest): String {
    val ci = pr.ciState?.uppercase() ?: return "ci"
    val workflows = pr.ciWorkflows
    if (workflows.isEmpty()) return ci.lowercase()
    val totalWf = workflows.size
    return when (ci) {
        "FAILURE", "ERROR" -> {
            val failedWf = workflows.count { it.failureCount > 0 }
            val totalFailedTasks = workflows.sumOf { it.failureCount }
            "${failedWf}/${totalWf}wf\u00B7${totalFailedTasks}"
        }
        "PENDING" -> {
            val doneWf = workflows.count { it.status == "SUCCESS" || it.status == "FAILURE" }
            "${doneWf}/${totalWf}wf"
        }
        "SUCCESS" -> "${totalWf}wf"
        else -> ci.lowercase()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrExpandedDetail(
    pr: OpenPullRequest,
    retryFlakyJob: RetryFlakyJob?,
    isRetrySubmitting: Boolean,
    isCiSubmitting: Boolean,
    onRetryCi: (() -> Unit)?,
    onRetryFlaky: (() -> Unit)?,
    onCancelRetryFlaky: (() -> Unit)?,
) {
    val context = LocalContext.current
    val statusColors = LocalGhprStatusColors.current
    val hasActiveJob = retryFlakyJob != null && retryFlakyJob.status == "active"
    val isCiFailure = pr.ciState?.uppercase() in listOf("FAILURE", "ERROR")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Workflow breakdown
        if (pr.ciWorkflows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pr.ciWorkflows.forEach { wf ->
                    WorkflowStatusRow(wf)
                }
            }
        } else if (pr.ciState != null) {
            Text(
                text = "No workflow details",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Retry job info
        if (retryFlakyJob != null) {
            val statusText = when (retryFlakyJob.status) {
                "active" -> "Retrying: ${retryFlakyJob.retriesRemaining}/${retryFlakyJob.totalRetries} remaining"
                "completed" -> "Retry completed"
                "exhausted" -> "Retries exhausted"
                "cancelled" -> "Retry cancelled"
                else -> "Retry: ${retryFlakyJob.status}"
            }
            val statusColor = when (retryFlakyJob.status) {
                "active" -> statusColors.pending
                "completed" -> statusColors.merged
                else -> statusColors.closed
            }
            Text(
                text = statusText,
                style = MonoStyle.codeSmall,
                color = statusColor,
            )
            retryFlakyJob.updatedAt?.let { updatedAt ->
                Text(
                    text = "Last retry: $updatedAt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Action buttons
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NeoButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, pr.url.toUri()))
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    Icons.Default.OpenInBrowser,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Open PR", style = MaterialTheme.typography.labelMedium)
            }
            if (onRetryCi != null && isCiFailure) {
                NeoButton(
                    onClick = onRetryCi,
                    enabled = !isCiSubmitting && !isRetrySubmitting,
                    containerColor = Color(0xFF3B82F6),
                    contentColor = Color.White,
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (onRetryFlaky != null && isCiFailure) {
                NeoButton(
                    onClick = onRetryFlaky,
                    enabled = !isRetrySubmitting && !isCiSubmitting && !hasActiveJob,
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color.White,
                ) {
                    Text("Retry x3", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (hasActiveJob && onCancelRetryFlaky != null) {
                NeoButton(
                    onClick = onCancelRetryFlaky,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun WorkflowStatusRow(wf: CIWorkflowInfo) {
    val statusColors = LocalGhprStatusColors.current
    val color = when {
        wf.failureCount > 0 -> statusColors.closed
        wf.pendingCount > 0 -> statusColors.pending
        else -> statusColors.merged
    }
    val statusText = when {
        wf.failureCount > 0 -> "${wf.failureCount} failed"
        wf.pendingCount > 0 -> "${wf.pendingCount} pending"
        else -> "${wf.successCount} passed"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = wf.name,
            style = MonoStyle.codeSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        StatusBadge(
            text = statusText,
            color = color,
        )
        if (wf.totalCount > 1) {
            Text(
                text = "${wf.successCount}/${wf.totalCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
