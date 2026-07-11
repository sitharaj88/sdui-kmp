package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.auth.StudioJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Covers the secure-by-default signing-secret policy in [StudioJwt.resolveSecret]. The production
 * risk this guards against: a deploy that forgets `STUDIO_JWT_SECRET` silently signing forgeable
 * admin/RBAC tokens with the public [StudioJwt.FALLBACK_SECRET].
 */
class StudioJwtTest {

    private val strongSecret = "x".repeat(StudioJwt.MIN_SECRET_LENGTH)

    @Test
    fun `strong configured secret is honored regardless of environment`() {
        assertEquals(strongSecret, StudioJwt.resolveSecret(strongSecret, sduiEnv = null))
        assertEquals(strongSecret, StudioJwt.resolveSecret(strongSecret, sduiEnv = "production"))
    }

    @Test
    fun `secret shorter than the minimum is rejected`() {
        val short = "x".repeat(StudioJwt.MIN_SECRET_LENGTH - 1)
        assertFailsWith<IllegalArgumentException> {
            StudioJwt.resolveSecret(short, sduiEnv = "production")
        }
    }

    @Test
    fun `missing secret fails fast outside a dev environment`() {
        assertFailsWith<IllegalStateException> { StudioJwt.resolveSecret(null, sduiEnv = null) }
        assertFailsWith<IllegalStateException> { StudioJwt.resolveSecret("", sduiEnv = "production") }
        assertFailsWith<IllegalStateException> { StudioJwt.resolveSecret(null, sduiEnv = "prod") }
    }

    @Test
    fun `missing secret is tolerated only in an explicit dev environment`() {
        for (env in StudioJwt.DEV_ENVS) {
            assertEquals(
                StudioJwt.FALLBACK_SECRET,
                StudioJwt.resolveSecret(configured = null, sduiEnv = env),
                "SDUI_ENV=$env should permit the dev fallback",
            )
            // Case-insensitive.
            assertEquals(StudioJwt.FALLBACK_SECRET, StudioJwt.resolveSecret(null, env.uppercase()))
        }
    }

    @Test
    fun `blank secret is treated as unset`() {
        assertEquals(StudioJwt.FALLBACK_SECRET, StudioJwt.resolveSecret("   ", sduiEnv = "dev"))
    }

    @Test
    fun `resolved dev secret produces a usable verifier`() {
        // Sanity: the resolved dev secret yields a verifier (construction does not throw).
        val jwt = StudioJwt(StudioJwt.resolveSecret(null, sduiEnv = "test"))
        assertNotNull(jwt.verifier())
    }
}
