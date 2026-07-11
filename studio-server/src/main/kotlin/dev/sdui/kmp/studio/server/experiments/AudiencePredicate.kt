package dev.sdui.kmp.studio.server.experiments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

/**
 * Audience-targeting predicate language.
 *
 * Designed as a small, closed sealed hierarchy of boolean operators over a flat
 * `Map<String, String>` context (typed by the caller — e.g. `country`, `appVersion`,
 * `tier`). Decisions captured in `docs/adr/0018-studio-ab-targeting-model.md`:
 *
 *  - Sealed AST instead of a string DSL or JSONLogic. Closed sealed interfaces give us
 *    exhaustive `when` branches in [evaluate], a versionable wire format via
 *    `kotlinx.serialization`'s `@SerialName` discriminator, and additive evolution that
 *    matches the protocol's red lines (never remove a case, only add).
 *  - Operates on a flat `Map<String, String>` because targeting attributes that reach the
 *    studio over HTTP headers are always strings; numeric comparisons are deferred to
 *    a future `Compare` case if a real use-case emerges.
 *  - No regex backreferences, no lookaheads — server-side regex evaluation is a DOS-amplifier
 *    when the predicate is editor-controlled. The route layer compiles each [MatchesRegex]
 *    pattern once at audience-create time and rejects pathological patterns before storing.
 */
@Serializable
public sealed interface AudiencePredicate {
    /** Exact equality on a context attribute. Returns false if the field is absent. */
    @Serializable
    @SerialName("equals")
    public data class Equals(public val field: String, public val value: String) : AudiencePredicate

    /** Membership test — true iff `context[field]` is in [values]. Empty list never matches. */
    @Serializable
    @SerialName("in")
    public data class In(public val field: String, public val values: List<String>) : AudiencePredicate

    /**
     * Regex match against a context field. Pattern is anchored implicitly via [Regex.matches].
     * False if the field is absent OR the pattern is invalid (defence-in-depth: a corrupted
     * row in the `audiences` table must not throw at evaluation time).
     */
    @Serializable
    @SerialName("matches_regex")
    public data class MatchesRegex(public val field: String, public val pattern: String) : AudiencePredicate

    /** Boolean AND. Empty list evaluates to true (vacuously). */
    @Serializable
    @SerialName("and")
    public data class And(public val predicates: List<AudiencePredicate>) : AudiencePredicate

    /** Boolean OR. Empty list evaluates to false (vacuously). */
    @Serializable
    @SerialName("or")
    public data class Or(public val predicates: List<AudiencePredicate>) : AudiencePredicate

    /** Negation of the inner predicate. */
    @Serializable
    @SerialName("not")
    public data class Not(public val child: AudiencePredicate) : AudiencePredicate
}

/**
 * Evaluate a predicate against a context. The context is a flat string-to-string map provided
 * by the calling server — the typical wiring pulls keys from `X-Sdui-Context-*` HTTP headers
 * (e.g. `X-Sdui-Context-Country: US` becomes `"country" -> "US"`).
 *
 * Never throws. A malformed regex returns false; a missing field returns false (or the case's
 * specific empty-collection semantics — see the individual operator KDoc).
 */
public fun AudiencePredicate.evaluate(context: Map<String, String>): Boolean = when (this) {
    is AudiencePredicate.Equals -> context[field] == value
    is AudiencePredicate.In -> context[field]?.let { it in values } ?: false
    is AudiencePredicate.MatchesRegex -> {
        val raw = context[field] ?: return@evaluate false
        // Compile-once via the shared guard cache — never recompile per request (a per-request
        // recompile of an editor-controlled pattern is a ReDoS amplifier on the anonymous
        // assign path). A pattern that cannot pass the guard evaluates to false, preserving the
        // "never throws" contract even for a corrupted `audiences` row.
        AudienceRegexGuard.compileCached(pattern)?.matches(raw) ?: false
    }
    is AudiencePredicate.And -> predicates.all { it.evaluate(context) }
    is AudiencePredicate.Or -> predicates.any { it.evaluate(context) }
    is AudiencePredicate.Not -> !child.evaluate(context)
}

/**
 * Walk this predicate tree and validate + compile every [AudiencePredicate.MatchesRegex]
 * pattern through [AudienceRegexGuard], throwing on the first pattern that is over-length,
 * structurally dangerous (nested unbounded quantifiers), or fails to compile.
 *
 * Called at audience-create time so pathological patterns are rejected with a `400` before they
 * are ever stored — a stored bad pattern would otherwise be recompiled and re-run per anonymous
 * `/screens/{id}/assign` request. Successful compiles are cached so the create-time compile is
 * the only one that happens.
 *
 * @throws IllegalArgumentException if any regex is too long, catastrophic, or invalid.
 */
public fun AudiencePredicate.validateRegexes() {
    when (this) {
        is AudiencePredicate.MatchesRegex -> AudienceRegexGuard.compileStrict(pattern)
        is AudiencePredicate.And -> predicates.forEach { it.validateRegexes() }
        is AudiencePredicate.Or -> predicates.forEach { it.validateRegexes() }
        is AudiencePredicate.Not -> child.validateRegexes()
        is AudiencePredicate.Equals, is AudiencePredicate.In -> Unit
    }
}

/**
 * Length- and complexity-bounded compiler + process-wide cache for audience regex patterns.
 *
 * Two guarantees this object exists to provide:
 *  1. **Bounded compilation.** Editor-controlled patterns are rejected at create time if they
 *     exceed [MAX_PATTERN_LENGTH] or contain nested unbounded quantifiers (`(a+)+`, `(a*)*`,
 *     `([a-z]+)*`, …) — the classic catastrophic-backtracking shape — before they can be stored
 *     and later recompiled/re-run against attacker-controlled `X-Sdui-Context-*` input.
 *  2. **Compile-once.** A [java.util.regex.Pattern] is compiled at most once per distinct
 *     pattern string; both create-time validation and per-request evaluation share the cache, so
 *     no request path recompiles a stored predicate.
 *
 * This is defence in depth: the assign route is separately gated behind a service token and a
 * rate limiter, and predicates originate from authenticated editors. The guard exists so a single
 * bad predicate can never become a stored, self-amplifying denial-of-service primitive.
 */
public object AudienceRegexGuard {
    /** Maximum accepted pattern length. Long editor patterns are almost always a mistake. */
    public const val MAX_PATTERN_LENGTH: Int = 512

    // Optional.empty() is a cached negative (pattern rejected) so a bad stored pattern is not
    // re-validated on every evaluation. Keyed by the raw pattern string; audience patterns are a
    // small, editor-bounded set so unbounded growth is not a practical concern.
    private val cache = ConcurrentHashMap<String, Optional<Regex>>()

    /**
     * Compile [pattern], enforcing the length + complexity guard. Caches and returns the
     * compiled [Regex] on success.
     *
     * @throws IllegalArgumentException if the pattern is over-length, contains nested unbounded
     *   quantifiers, or is not a valid regular expression.
     */
    public fun compileStrict(pattern: String): Regex {
        cache[pattern]?.orElse(null)?.let { return it }
        // Not cached, or cached as a negative: (re)run validation so the caller gets a precise
        // rejection reason. The length/complexity checks fail fast before the expensive compile.
        val compiled = validateAndCompile(pattern)
        cache[pattern] = Optional.of(compiled)
        return compiled
    }

    /**
     * Compile [pattern] through the same guard, but never throw: returns `null` for any pattern
     * that fails validation. Negative results are cached. Use on the evaluation hot path where a
     * bad pattern must degrade to "no match" rather than crash.
     */
    public fun compileCached(pattern: String): Regex? =
        cache.computeIfAbsent(pattern) { p ->
            Optional.ofNullable(runCatching { validateAndCompile(p) }.getOrNull())
        }.orElse(null)

    private fun validateAndCompile(pattern: String): Regex {
        require(pattern.length <= MAX_PATTERN_LENGTH) {
            "audience regex too long: ${pattern.length} characters (max $MAX_PATTERN_LENGTH)"
        }
        require(!hasCatastrophicNesting(pattern)) {
            "audience regex rejected: nested unbounded quantifiers risk catastrophic backtracking (ReDoS)"
        }
        return try {
            Regex(pattern)
        } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException("audience regex failed to compile: ${e.message}", e)
        }
    }

    /**
     * Heuristic detector for the canonical ReDoS shape: an unbounded quantifier applied to a
     * group whose own body contains an unbounded quantifier (e.g. `(a+)+`, `(a*)*`, `([a-z]+)*`,
     * `(.*)+`). Not a full analyser — it deliberately covers the exponential-backtracking family
     * used in ReDoS proofs-of-concept rather than every pathological grammar.
     */
    @Suppress("ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod")
    internal fun hasCatastrophicNesting(pattern: String): Boolean {
        // Per open group: has this group contained an unbounded quantifier at its own level?
        val unboundedAtGroupLevel = ArrayDeque<Boolean>()
        var i = 0
        val n = pattern.length
        while (i < n) {
            when (pattern[i]) {
                '\\' -> i += 2 // skip the escaped character
                '[' -> i = skipCharClass(pattern, i)
                '(' -> {
                    unboundedAtGroupLevel.addLast(false)
                    i++
                }
                ')' -> {
                    val innerUnbounded = if (unboundedAtGroupLevel.isEmpty()) {
                        false
                    } else {
                        unboundedAtGroupLevel.removeLast()
                    }
                    val (unboundedQuant, consumed) = quantifierAt(pattern, i + 1)
                    if (innerUnbounded && unboundedQuant) return true
                    if (unboundedQuant && unboundedAtGroupLevel.isNotEmpty()) {
                        unboundedAtGroupLevel[unboundedAtGroupLevel.lastIndex] = true
                    }
                    i += 1 + consumed
                }
                '+', '*' -> {
                    if (unboundedAtGroupLevel.isNotEmpty()) {
                        unboundedAtGroupLevel[unboundedAtGroupLevel.lastIndex] = true
                    }
                    i++
                }
                '{' -> {
                    val (unboundedQuant, consumed) = quantifierAt(pattern, i)
                    if (unboundedQuant && unboundedAtGroupLevel.isNotEmpty()) {
                        unboundedAtGroupLevel[unboundedAtGroupLevel.lastIndex] = true
                    }
                    i += consumed.coerceAtLeast(1)
                }
                else -> i++
            }
        }
        return false
    }

    private fun skipCharClass(pattern: String, start: Int): Int {
        var i = start + 1
        val n = pattern.length
        if (i < n && pattern[i] == '^') i++
        if (i < n && pattern[i] == ']') i++ // a literal ']' as the first class member
        while (i < n && pattern[i] != ']') {
            if (pattern[i] == '\\') i++
            i++
        }
        return i + 1 // consume the closing ']'
    }

    // Returns (isUnboundedQuantifier, charsConsumed) for a quantifier starting at [j], or
    // (false, 0) if there is no quantifier there. `*`, `+` and `{n,}` are unbounded; `?` and
    // `{n}` / `{n,m}` are bounded.
    private fun quantifierAt(pattern: String, j: Int): Pair<Boolean, Int> {
        if (j >= pattern.length) return false to 0
        return when (pattern[j]) {
            '*', '+' -> true to 1
            '?' -> false to 1
            '{' -> {
                val close = pattern.indexOf('}', j)
                if (close < 0) return false to 0
                val body = pattern.substring(j + 1, close)
                val comma = body.indexOf(',')
                val unbounded = comma >= 0 && body.substring(comma + 1).isBlank()
                unbounded to (close - j + 1)
            }
            else -> false to 0
        }
    }
}
