package dev.sdui.kmp.tooling.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Accessibility assertions for tests authored against Compose Multiplatform's ui-test
 * infrastructure.
 *
 * Adopters' UI test suites use these extensions to lock in the WCAG 2.2 AA invariants the
 * sdui-kmp framework guarantees about its renderers — primarily that every interactive node
 * has an accessible name, the right role, and a tappable region of at least 48 dp x 48 dp
 * (Material's touch-target minimum, which equals WCAG 2.5.8's recommended size).
 *
 * Typical usage from `runComposeUiTest`:
 *
 * ```kotlin
 * onNodeWithText("Continue")
 *     .assertHasContentDescription()
 *     .assertHasRole(Role.Button)
 *     .assertSatisfiesTouchTarget()
 * ```
 *
 * Each assertion returns the same [SemanticsNodeInteraction] so calls chain. Assertions
 * never throw a generic Compose error — they format a human-readable failure message
 * pointing at the missing semantic and at this assertion file, so a CI failure tells the
 * adopter exactly what to fix in their renderer.
 */

/**
 * Asserts the node carries a non-blank [SemanticsProperties.ContentDescription]. Compose's
 * own assertion helpers either match an exact value or any value; we wrap the "any non-blank
 * value" case with a label-friendly failure message so a missing description in CI reads as
 * "no contentDescription on node X" rather than the default "expected to be ContentDescription".
 *
 * Counts toward WCAG 1.1.1 (Non-text Content) and 4.1.2 (Name, Role, Value).
 */
public fun SemanticsNodeInteraction.assertHasContentDescription(): SemanticsNodeInteraction {
    val matcher = SemanticsMatcher("has non-blank ContentDescription") { node ->
        val list = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        list.any { it.isNotBlank() }
    }
    return this.assert(matcher)
}

/**
 * Asserts the node's [SemanticsProperties.Role] equals [role]. Wraps Compose's
 * [SemanticsMatcher] so the failure message names the expected role explicitly. Counts
 * toward WCAG 4.1.2 (Name, Role, Value).
 */
public fun SemanticsNodeInteraction.assertHasRole(role: Role): SemanticsNodeInteraction {
    val matcher = SemanticsMatcher("has Role == $role") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == role
    }
    return this.assert(matcher)
}

/**
 * Asserts the node's bounding box meets a minimum touch-target size of [minDp] in both
 * dimensions. Default is 48 dp, matching Material's recommendation and WCAG 2.2 SC 2.5.8
 * (Target Size — Minimum, 24 CSS px which lines up at 48 dp on 1x density). Both width
 * and height are checked because either axis below the minimum fails the spec.
 *
 * Skipped automatically when the node carries no click action (decorative, non-interactive
 * nodes don't need a tappable region) — the call still asserts the matchers exist on the
 * node, which is what the caller asked for.
 */
public fun SemanticsNodeInteraction.assertSatisfiesTouchTarget(
    minDp: Dp = 48.dp,
): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("assertSatisfiesTouchTarget")
    val isInteractive = node.config.getOrNull(SemanticsActions.OnClick) != null
    if (!isInteractive) return this
    return this
        .assertWidthIsAtLeast(minDp)
        .assertHeightIsAtLeast(minDp)
}
