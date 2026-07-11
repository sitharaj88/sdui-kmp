package dev.sdui.kmp.runtime

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The buckets sdui-kmp distinguishes when checking whether a side-effect that touches user
 * data is permitted.
 *
 * Scopes are intentionally coarse — every adopter's privacy posture maps cleanly onto these
 * four buckets, and a coarse vocabulary survives policy churn better than a fine-grained one.
 * Fine-grained product-specific scopes belong in adopter code, not here.
 *
 *  * [Analytics] — product-usage events sent to a first-party analytics pipeline.
 *  * [Telemetry] — runtime instrumentation (renders, action dispatches, unknown-node fallbacks)
 *     forwarded to OpenTelemetry, Datadog, or a similar observability backend.
 *  * [Personalization] — server-side adaptation of UI trees based on the user's history.
 *  * [Crash] — automatic crash / non-fatal error reporting (Crashlytics, Sentry, ...).
 */
public enum class ConsentScope { Analytics, Telemetry, Personalization, Crash }

/**
 * Synchronous lookup the runtime calls before emitting consent-bound side effects.
 *
 * Adopters wire one implementation backed by their consent management platform (CMP) into
 * [LocalConsentSource]. The contract is intentionally synchronous: the runtime never makes
 * a network round-trip on the consent path; the CMP must cache the user's choice locally.
 *
 * Implementations should be cheap to call — the runtime queries them on every telemetry
 * emission and many UI events per second.
 */
public interface ConsentSource {
    /**
     * Returns `true` if the user has granted consent for the supplied [scope]; `false`
     * otherwise. Implementations must never throw — an unknown scope must read as `false`.
     */
    public fun isGranted(scope: ConsentScope): Boolean
}

/**
 * Default [ConsentSource]: no consent for any scope. Gating telemetry on this source means
 * absolutely nothing leaves the device, which is the safest possible default for a fresh
 * checkout or a server-rendered preview.
 */
public object NoConsent : ConsentSource {
    override fun isGranted(scope: ConsentScope): Boolean = false
}

/**
 * Convenience [ConsentSource] for tests, sample apps, and developer tooling: every scope
 * reports granted. Production hosts must replace this with a CMP-backed implementation.
 */
public object FullConsent : ConsentSource {
    override fun isGranted(scope: ConsentScope): Boolean = true
}

/**
 * Active [ConsentSource] for the current composition. Defaults to [NoConsent] so a host that
 * forgets to wire its CMP fails closed — telemetry, analytics, personalization, and crash
 * reporting are all suppressed until the host opts in.
 *
 * Wire it once at the host root:
 *
 * ```kotlin
 * CompositionLocalProvider(LocalConsentSource provides myCmp.toConsentSource()) {
 *     SduiHost(...)
 * }
 * ```
 *
 * The runtime's consent-aware telemetry decorator reads this local at composition time and
 * gates emission accordingly. Pure-Kotlin (non-Composable) call sites bypass the local and
 * emit unconditionally — the host is responsible for not constructing such pipelines without
 * a CMP check upstream.
 */
public val LocalConsentSource: ProvidableCompositionLocal<ConsentSource> =
    staticCompositionLocalOf { NoConsent }
