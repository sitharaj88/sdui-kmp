package dev.sdui.kmp.sample.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the Android sample app.
 *
 * Launches `MainActivity`, waits for the home screen greeting emitted by `:samples:sample-server`
 * (`"Welcome to sdui-kmp"`), and asserts the navigation buttons emitted alongside it are
 * present. The test depends on a reachable sample-server (the Android emulator routes
 * `localhost` → `10.0.2.2`). It is therefore deferred from CI until an emulator-backed
 * lane is provisioned — `assembleDebugAndroidTest` keeps the harness compiling in the
 * meantime so future regressions in the device-side test surface fail at PR time.
 */
@RunWith(AndroidJUnit4::class)
class SampleAppInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun home_screen_renders_welcome_and_login_button() {
        // The sample-server greeting (see Main.kt#homeScreen). Waiting on the text rather
        // than a hardcoded sleep keeps the harness deterministic when the network round-
        // trip takes a variable amount of time.
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule
                .onAllNodes(hasText("Welcome to sdui-kmp"))
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }

        composeRule.onNodeWithText("Welcome to sdui-kmp").assertIsDisplayed()
        composeRule.onNodeWithText("Log in").assertIsDisplayed()
    }
}
