package org.yapmessenger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YapUiTest {
    // Skip first-run onboarding so these tests start on the Chats screen.
    private val seedProfile = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("yap_profile", 0)
                .edit().putBoolean("onboarded", true).putString("name", "Test").commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(seedProfile).around(compose)

    private fun openChat() = compose.onNodeWithText("Nearby Yap phone").performScrollTo().performClick()
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun preview() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Preview the full design").performScrollTo().performClick()
        compose.onNodeWithText("Design preview · Sample data").assertIsDisplayed()
    }

    @Test fun pairingPageLoadsNativeIdentityAndScannableCode() {
        compose.onNodeWithText("Add a friend").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Your Yap pairing code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Your Yap pairing code").assertIsDisplayed()
        compose.onNodeWithText("Scan their code").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Your fingerprint").performScrollTo().assertIsDisplayed()
    }

    @Test fun composerChecksUtf8BytesAndKeyboardSendDoesNotBypassValidation() {
        openChat()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("😀".repeat(6))
        compose.onNodeWithText("24 / 20 UTF-8 bytes · Shorten your message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("24 / 20 UTF-8 bytes · Shorten your message").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("😀".repeat(5))
        compose.onNodeWithText("20 / 20 UTF-8 bytes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun systemBackReturnsThroughControlsAndConversationToChats() {
        openChat()
        compose.onNodeWithContentDescription("Connection controls").performClick()
        compose.onNodeWithText("Connections").assertIsDisplayed()
        back()
        compose.onNodeWithText("Message").assertExists()
        back()
        compose.onNodeWithText("All").assertIsDisplayed()
    }

    @Test fun networkAndSettingsRemainReachableAndTruthful() {
        compose.onNodeWithText("Network").performClick()
        compose.onNodeWithText("Your local network").assertIsDisplayed()
        compose.onNodeWithText("Internet · planned").assertDoesNotExist()
        compose.onNodeWithText("Connections, not physical positions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Network details").performClick()
        compose.onNodeWithText("Advanced: internet relay and test chat").performScrollTo().performClick()
        compose.onNodeWithText("Relay URL").performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Privacy & identity").performScrollTo().performClick()
        compose.onNodeWithText("Your identity").assertIsDisplayed()
        back()
        back()
        compose.onNodeWithText("All").assertIsDisplayed()
    }

    @Test fun previewIncludesGroupAndJourneyScreensWithTransportLegend() {
        preview()
        compose.onNodeWithText("Ravi").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("meeting_notes.pdf").performScrollTo().performClick()
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.onNodeWithText("Recipient acknowledged receipt").performScrollTo().assertIsDisplayed()
        back()
        back()
        compose.onNodeWithText("Maya").performScrollTo().performClick()
        compose.onNodeWithText("Internet").assertIsDisplayed()
        back()
        compose.onNodeWithText("Anfield Crew").performScrollTo().performClick()
        compose.onNodeWithText("Anfield Crew").performClick()
        compose.onNodeWithText("Membership requests").performScrollTo().assertIsDisplayed()
        back()
        back()
        compose.onNodeWithText("Network").performClick()
        compose.onNodeWithText("3 devices").assertIsDisplayed()
        compose.onNodeWithText("Via Ravi · Local mesh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exit preview").performClick()
        compose.onNodeWithText("Ravi").assertDoesNotExist()
    }

    @Test fun draftSurvivesActivityRecreation() {
        openChat()
        compose.onNode(hasSetTextAction()).performTextInput("Keep my draft")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextContains("Keep my draft")
        compose.onNodeWithContentDescription("Connection controls").assertIsDisplayed()
    }

    @Test fun backExitsPreviewAtTheChatsRoot() {
        preview()
        back()
        compose.onNodeWithText("Design preview · Sample data").assertDoesNotExist()
    }

    @Test fun searchAndGroupFiltersHaveHonestEmptyStates() {
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Groups are coming later").assertIsDisplayed()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("missing name")
        compose.onNodeWithText("No chats here yet").assertIsDisplayed()
    }
}
