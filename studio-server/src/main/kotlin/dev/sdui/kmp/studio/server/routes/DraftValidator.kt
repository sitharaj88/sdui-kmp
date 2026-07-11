package dev.sdui.kmp.studio.server.routes

import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.tooling.cli.ProtocolSnapshot
import dev.sdui.kmp.tooling.cli.Violation
import dev.sdui.kmp.tooling.cli.captureProtocolSnapshot
import dev.sdui.kmp.tooling.cli.lintProtocol
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * Result of [DraftValidator.validate]. Either the parsed [Screen] (good to write) or a list of
 * human-readable violation messages (400 to caller).
 */
public sealed interface DraftValidationResult {
    public data class Valid(public val screen: Screen, public val canonicalJson: String) : DraftValidationResult
    public data class Invalid(public val violations: List<String>) : DraftValidationResult
}

/**
 * Two-layer validation for editor-submitted screen JSON:
 *
 *  1. **Structural** — decode the body via `Screen.serializer()`. Catches malformed JSON, missing
 *     required fields, unknown discriminator values, wrong field types. The bulk of editor errors
 *     surface here.
 *  2. **Protocol drift** — at construction time, compare the deployed `:protocol` against the
 *     baseline `protocol-snapshot.json` via `:tooling-cli`'s [lintProtocol]. If the studio is
 *     somehow running against a drifted protocol (e.g. a pre-release server in front of a stable
 *     baseline) any *future* draft accepted here might reference fields the baseline does not
 *     know about. We log a warning in that case but accept drafts; an admin must update the
 *     baseline before drift becomes a hard error.
 *
 * The baseline file is loaded from the classpath resource `/protocol-snapshot.json`. Tests pass
 * a captured snapshot directly so they don't require a packaged resource.
 */
public class DraftValidator internal constructor(
    private val baseline: ProtocolSnapshot?,
) {
    private val logger = LoggerFactory.getLogger(DraftValidator::class.java)

    init {
        if (baseline != null) {
            val drift: List<Violation> = lintProtocol(baseline, captureProtocolSnapshot())
            if (drift.isNotEmpty()) {
                logger.warn(
                    "studio is running against a protocol that differs from the committed baseline " +
                        "by {} change(s); update protocol-snapshot.json or downgrade :protocol",
                    drift.size,
                )
            }
        } else {
            logger.warn(
                "no baseline protocol snapshot available; studio is accepting drafts without " +
                    "cross-checking the wire format against the committed baseline",
            )
        }
    }

    /** Validate a draft body. */
    public fun validate(body: JsonElement): DraftValidationResult = try {
        val screen: Screen = SduiJson.decodeFromJsonElement(Screen.serializer(), body)
        val canonical: String = SduiJson.encodeToString(Screen.serializer(), screen)
        DraftValidationResult.Valid(screen, canonical)
    } catch (e: SerializationException) {
        DraftValidationResult.Invalid(listOf(e.message ?: "draft body did not decode as Screen"))
    } catch (e: IllegalArgumentException) {
        DraftValidationResult.Invalid(listOf(e.message ?: "draft body rejected by Screen validator"))
    }

    public companion object {
        /** Build using the classpath baseline. Returns a permissive validator if absent. */
        public fun fromClasspath(): DraftValidator {
            val resource = DraftValidator::class.java.getResourceAsStream(BASELINE_RESOURCE)
            val baseline = resource?.use { stream ->
                ProtocolSnapshot.Json.decodeFromString(
                    ProtocolSnapshot.serializer(),
                    stream.bufferedReader().readText(),
                )
            }
            return DraftValidator(baseline)
        }

        /** Build with an explicit baseline. Used by tests. */
        public fun withBaseline(baseline: ProtocolSnapshot?): DraftValidator = DraftValidator(baseline)

        private const val BASELINE_RESOURCE: String = "/protocol-snapshot.json"
    }
}
