package ai.noid.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoidNavigationBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigationExposesArabicLabelsAndSelectedDestination() {
        composeRule.setContent {
            MaterialTheme {
                NoidNavigationBar(
                    selectedDestination = NoidDestination.MEMORY,
                    showClearAction = false,
                    onDestinationSelected = {},
                    onClearConversation = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("التنقل الرئيسي").assertExists()
        composeRule.onNodeWithText("الذاكرة").assertIsSelected()
        composeRule.onNodeWithText("فحص APK").assertIsNotSelected()
        composeRule.onNodeWithText("التعلّم").assertExists()
        composeRule.onNodeWithText("تطوير NOID").assertExists()
    }

    @Test
    fun selectingOffscreenDestinationNotifiesHost() {
        var selected = NoidDestination.CHAT
        composeRule.setContent {
            MaterialTheme {
                NoidNavigationBar(
                    selectedDestination = selected,
                    showClearAction = false,
                    onDestinationSelected = { selected = it },
                    onClearConversation = {}
                )
            }
        }

        composeRule.onNodeWithText("الصوت").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(NoidDestination.VOICE, selected)
        }
    }

    @Test
    fun clearActionHasAnExplicitAccessibleDescription() {
        composeRule.setContent {
            MaterialTheme {
                NoidNavigationBar(
                    selectedDestination = NoidDestination.CHAT,
                    showClearAction = true,
                    onDestinationSelected = {},
                    onClearConversation = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("مسح سجل المحادثة").assertExists()
    }
}
