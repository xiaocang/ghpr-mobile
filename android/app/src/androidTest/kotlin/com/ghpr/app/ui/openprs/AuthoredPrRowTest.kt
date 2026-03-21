package com.ghpr.app.ui.openprs

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.data.PrCategory
import com.ghpr.app.ui.theme.GhprTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthoredPrRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ciFailureRowExpandsAndCollapsesWithoutRevealingSwipeWhileExpanded() {
        composeRule.mainClock.autoAdvance = false
        val pr = pr()
        setRowContent(pr)

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealClosedStateDescription))

        composeRule.onNodeWithTag(prCardTag(pr)).performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithTag(prExpandedDetailTag(pr)).assertExists()
        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealClosedStateDescription))

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr)).performTouchInput { swipeLeft() }
        composeRule.mainClock.advanceTimeBy(250)

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealClosedStateDescription))

        composeRule.onNodeWithTag(prCardTag(pr)).performClick()
        composeRule.mainClock.advanceTimeBy(300)

        composeRule.onNodeWithTag(prExpandedDetailTag(pr)).assertDoesNotExist()
    }

    @Test
    fun expandingRowClosesAnyPreviouslyRevealedSwipeOffset() {
        composeRule.mainClock.autoAdvance = false
        val pr = pr()
        setRowContent(pr)

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr)).performTouchInput { swipeLeft() }
        composeRule.mainClock.advanceTimeBy(250)

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealOpenStateDescription))

        composeRule.onNodeWithTag(prCardTag(pr)).performClick()
        composeRule.mainClock.advanceTimeBy(300)

        composeRule.onNodeWithTag(prExpandedDetailTag(pr)).assertExists()
        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealClosedStateDescription))
    }

    @Test
    fun nonFailureRowStillExpandsAndCollapses() {
        composeRule.mainClock.autoAdvance = false
        val pr = pr(ciState = "SUCCESS")
        setRowContent(pr)

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealClosedStateDescription))

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr)).performTouchInput { swipeLeft() }
        composeRule.mainClock.advanceTimeBy(250)

        composeRule.onNodeWithTag(prSwipeForegroundTag(pr))
            .assert(hasStateDescription(SwipeRevealClosedStateDescription))

        composeRule.onNodeWithTag(prCardTag(pr)).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(prExpandedDetailTag(pr)).assertExists()

        composeRule.onNodeWithTag(prCardTag(pr)).performClick()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(prExpandedDetailTag(pr)).assertDoesNotExist()
    }

    private fun setRowContent(
        pr: OpenPullRequest,
        initiallyExpanded: Boolean = false,
    ): MutableState<Boolean> {
        val expandedState = mutableStateOf(initiallyExpanded)
        composeRule.setContent {
            GhprTheme {
                AuthoredPrRow(
                    pr = pr,
                    retryFlakyJob = null,
                    isRetrySubmitting = false,
                    isCiSubmitting = false,
                    isExpanded = expandedState.value,
                    onToggleExpand = { expandedState.value = !expandedState.value },
                    onRetryCi = {},
                    onRetryFlaky = {},
                    onCancelRetryFlaky = {},
                )
            }
        }
        return expandedState
    }

    private fun pr(ciState: String = "FAILURE") =
        OpenPullRequest(
            number = 1,
            title = "PR #1",
            url = "https://github.com/owner/repo/pull/1",
            isDraft = false,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
            authorLogin = "user",
            authorAvatarUrl = "",
            repoOwner = "owner",
            repoName = "repo",
            ciState = ciState,
            category = PrCategory.AUTHORED,
        )

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
