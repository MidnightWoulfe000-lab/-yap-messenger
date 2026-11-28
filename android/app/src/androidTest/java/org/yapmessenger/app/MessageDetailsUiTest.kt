package org.yapmessenger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDetailsUiTest {
    private val secure by lazy { SecureModel(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application) }
    // An already-onboarded profile, so YapApp opens on the Chats screen.
    private val profile by lazy {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        context.getSharedPreferences("yap_profile", 0).edit().putBoolean("onboarded", true).putString("name", "Test").commit()
        ProfileStore(context)
    }
    @get:Rule val compose = createComposeRule()

    @Test fun previewCannotSend() {
        compose.setContent { YapTheme { YapApp(secure = secure, discoverMesh = {}, scan = {}, share = {}, profile = profile) } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Preview the full design").performScrollTo().performClick()
        compose.onNodeWithText("Ravi").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test fun networkKeepsYourPhoneCentred() {
        compose.setContent { YapTheme { YapApp(secure = secure, discoverMesh = {}, scan = {}, share = {}, profile = profile) } }
        compose.onNodeWithText("Network").performClick()
        fun checkCentre() {
            val map = compose.onNodeWithTag("network-map").fetchSemanticsNode().boundsInRoot
            val phone = compose.onNodeWithTag("network-self").fetchSemanticsNode().boundsInRoot
            assertTrue("Phone must stay horizontally centred", kotlin.math.abs(map.center.x - phone.center.x) <= 1f)
            assertTrue("Phone must stay vertically centred", kotlin.math.abs(map.center.y - phone.center.y) <= 1f)
        }
        checkCentre()
    }
}
