package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.db.StudioDatabase
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Covers the secure-by-default H2-fallback policy in [StudioDatabase.requireH2FallbackAllowed]
 * (PRODUCTION_READINESS #12). The production risk this guards against: a deploy that forgets
 * `JDBC_URL` silently booting on ephemeral in-memory H2 that wipes drafts, versions, RBAC, and
 * the audit log on every restart. Mirrors [dev.sdui.kmp.studio.server.auth.StudioJwt]'s
 * `resolveSecret` policy test so the two fail-fast gates stay in lockstep.
 */
class StudioDatabaseFallbackTest {

    @Test
    fun `missing JDBC_URL fails fast outside a dev environment`() {
        assertFailsWith<IllegalStateException> { StudioDatabase.requireH2FallbackAllowed(sduiEnv = null) }
        assertFailsWith<IllegalStateException> { StudioDatabase.requireH2FallbackAllowed(sduiEnv = "production") }
        assertFailsWith<IllegalStateException> { StudioDatabase.requireH2FallbackAllowed(sduiEnv = "prod") }
        assertFailsWith<IllegalStateException> { StudioDatabase.requireH2FallbackAllowed(sduiEnv = "staging") }
    }

    @Test
    fun `missing JDBC_URL is tolerated only in an explicit dev environment`() {
        for (env in StudioDatabase.DEV_ENVS) {
            // Does not throw.
            StudioDatabase.requireH2FallbackAllowed(sduiEnv = env)
            // Case-insensitive.
            StudioDatabase.requireH2FallbackAllowed(sduiEnv = env.uppercase())
        }
    }
}
