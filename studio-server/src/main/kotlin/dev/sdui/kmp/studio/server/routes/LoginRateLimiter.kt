package dev.sdui.kmp.studio.server.routes

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-`(client IP + email)` failed-login lockout with progressive back-off, protecting
 * `/admin/auth/login` from credential stuffing and the bcrypt-verify CPU amplification each
 * attempt causes.
 *
 * The coarse [dev.sdui.kmp.auth.rs256.RateLimitPlugin] on the login route caps total attempts
 * per source IP, but cannot key on the target email — its `keyExtractor` runs before the request
 * body is read. This limiter closes that gap: it tracks failures per attacker/target pair and,
 * once [failureThreshold] consecutive failures accumulate inside [failureWindowSeconds], locks the
 * pair out for a doubling window ([baseLockoutSeconds], capped at [maxLockoutSeconds]). A
 * successful login clears the pair immediately.
 *
 * **Single-instance only.** State lives in an in-process [ConcurrentHashMap]; behind a load
 * balancer each replica enforces its own counters, so an attacker multiplies their budget by the
 * replica count. A multi-replica deployment must back this with a shared store (e.g. Redis
 * `INCR`+`EXPIRE`).
 *
 * @param failureThreshold consecutive failures tolerated before the pair is locked out.
 * @param baseLockoutSeconds lockout length at the threshold; it doubles per additional failure.
 * @param maxLockoutSeconds ceiling on the doubling back-off.
 * @param failureWindowSeconds idle interval after which a pair's failure count is forgotten.
 * @param clock wall-clock source in epoch millis; overridable so tests need not sleep.
 */
public class LoginRateLimiter(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val baseLockoutSeconds: Long = DEFAULT_BASE_LOCKOUT_SECONDS,
    private val maxLockoutSeconds: Long = DEFAULT_MAX_LOCKOUT_SECONDS,
    private val failureWindowSeconds: Long = DEFAULT_FAILURE_WINDOW_SECONDS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Attempts(
        var failures: Int,
        var lockedUntilMs: Long,
        var lastFailureMs: Long,
    )

    private val attempts = ConcurrentHashMap<String, Attempts>()

    /**
     * Compose the bucket key for a login attempt. Both the IP (attacker) and the email (target)
     * are part of the key so one target's lockout never denies an unrelated login.
     */
    public fun key(ip: String?, email: String): String = "${ip ?: "unknown"}|$email"

    /**
     * Seconds the caller must wait if [key] is currently locked out, or `null` if a login attempt
     * may proceed. Call this before the (expensive) password check so a locked-out pair never
     * reaches bcrypt.
     */
    public fun retryAfterSeconds(key: String): Long? {
        val state = attempts[key] ?: return null
        val now = clock()
        if (now - state.lastFailureMs > failureWindowSeconds * MILLIS_PER_SECOND) {
            attempts.remove(key)
            return null
        }
        val remainingMs = state.lockedUntilMs - now
        return if (remainingMs > 0) {
            (remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        } else {
            null
        }
    }

    /** Record a failed login for [key], extending the progressive lockout once past threshold. */
    public fun recordFailure(key: String) {
        val now = clock()
        attempts.compute(key) { _, existing ->
            val state = if (existing == null ||
                now - existing.lastFailureMs > failureWindowSeconds * MILLIS_PER_SECOND
            ) {
                Attempts(failures = 0, lockedUntilMs = 0L, lastFailureMs = now)
            } else {
                existing
            }
            state.failures += 1
            state.lastFailureMs = now
            if (state.failures >= failureThreshold) {
                val overshoot = (state.failures - failureThreshold).coerceAtMost(MAX_BACKOFF_SHIFT)
                val lockSeconds = (baseLockoutSeconds shl overshoot).coerceAtMost(maxLockoutSeconds)
                state.lockedUntilMs = now + lockSeconds * MILLIS_PER_SECOND
            }
            state
        }
    }

    /** Clear all failure state for [key] after a successful login. */
    public fun recordSuccess(key: String) {
        attempts.remove(key)
    }

    /** Default lockout policy tuning. */
    public companion object {
        /** Consecutive failures tolerated before lockout kicks in. */
        public const val DEFAULT_FAILURE_THRESHOLD: Int = 5

        /** Lockout length at the threshold, in seconds; doubles per subsequent failure. */
        public const val DEFAULT_BASE_LOCKOUT_SECONDS: Long = 30L

        /** Ceiling on the doubling back-off, in seconds (15 minutes). */
        public const val DEFAULT_MAX_LOCKOUT_SECONDS: Long = 900L

        /** Idle interval after which a pair's failure count is forgotten, in seconds. */
        public const val DEFAULT_FAILURE_WINDOW_SECONDS: Long = 900L

        private const val MAX_BACKOFF_SHIFT: Int = 16
        private const val MILLIS_PER_SECOND: Long = 1_000L
    }
}
