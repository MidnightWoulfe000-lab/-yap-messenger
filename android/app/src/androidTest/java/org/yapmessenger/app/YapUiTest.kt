package org.yapmessenger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun preview() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Preview the full design").performScrollTo().performClick()
        compose.onNodeWithText("Design preview · Sample data").assertIsDisplayed()
    }

    @Test fun pairingPageLoadsNativeIdentityAndScannableCode() {
        compose.onNodeWithText("Add a friend").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Your Yap pairing code", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Your Yap pairing code", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Scan their code").performScrollTo().assertIsEnabled()
        // The fingerprint now sits under "Other ways to add a friend", which starts collapsed.
        compose.onNodeWithText("Other ways to add a friend").performScrollTo().performClick()
        compose.onNodeWithText("Your fingerprint").performScrollTo().assertIsDisplayed()
    }

    // The legacy chat is gone, so this walks the same path through the preview's sample conversation.
    @Test fun systemBackReturnsThroughControlsAndConversationToChats() {
        preview()
        compose.onNodeWithText("Ravi").performScrollTo().performClick()
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
        compose.onNodeWithText("Advanced: relay and nearby log").performScrollTo().performClick()
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

    // The legacy chat's draft lived in its own state holder. An encrypted chat keeps its draft in the
    // process-wide SecureModel, and the preview's composer is disabled, so a draft cannot be typed in a
    // sample conversation. This checks that the encrypted draft survives the activity being recreated, and
    // that the open screen survives too. (Typing into a real encrypted chat needs a paired contact.)
    @Test fun draftSurvivesActivityRecreation() {
        val secure = SecureModel.get(compose.activity.application)
        compose.runOnUiThread { secure.draft = "Keep my draft" }
        preview()
        compose.onNodeWithText("Ravi").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Connection controls").assertIsDisplayed()
        compose.runOnUiThread { assertEquals("Keep my draft", SecureModel.get(compose.activity.application).draft) }
        compose.runOnUiThread { secure.draft = "" }
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
