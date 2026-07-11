package dev.sdui.kmp.runtime

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * Android-only companion to [rememberNavigator] that hooks the system back gesture into
 * [StackNavigator.pop]. Called from [SduiHost] on Android; on other platforms the symbol
 * does not exist, so host code guards with `expect/actual` if it needs cross-platform wiring.
 */
@Composable
public fun InstallAndroidBackHandler(navigator: StackNavigator) {
    val route by navigator.current
    BackHandler(enabled = route != null && navigator.snapshot().size > 1) {
        navigator.pop()
    }
}
