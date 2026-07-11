package dev.sdui.kmp.tooling.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LintTest {

    private fun snapshot(
        sealed: Map<String, ProtocolSnapshot.SealedHierarchy> = emptyMap(),
        enums: Map<String, List<String>> = emptyMap(),
        dataClasses: Map<String, ProtocolSnapshot.DataClassShape> = emptyMap(),
    ) = ProtocolSnapshot(
        sealedHierarchies = sealed,
        enums = enums,
        dataClasses = dataClasses,
    )

    private fun cls(vararg fields: Pair<String, ProtocolSnapshot.FieldShape>) =
        ProtocolSnapshot.DataClassShape(fields = mapOf(*fields))

    private fun field(type: String, nullable: Boolean = false, optional: Boolean = false) =
        ProtocolSnapshot.FieldShape(type, nullable, optional)

    @Test
    fun identical_snapshots_have_no_violations() {
        val s = snapshot(
            dataClasses = mapOf("Screen" to cls("id" to field("kotlin.String"))),
        )
        assertEquals(emptyList(), lintProtocol(s, s))
    }

    @Test
    fun removed_data_class_is_a_violation() {
        val old = snapshot(dataClasses = mapOf("Screen" to cls()))
        val new = snapshot()
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.RemovedType, violations[0].kind)
        assertEquals("Screen", violations[0].path)
    }

    @Test
    fun removed_field_is_a_violation() {
        val old = snapshot(
            dataClasses = mapOf(
                "Screen" to cls("id" to field("kotlin.String"), "title" to field("kotlin.String")),
            ),
        )
        val new = snapshot(
            dataClasses = mapOf("Screen" to cls("id" to field("kotlin.String"))),
        )
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.RemovedField, violations[0].kind)
        assertEquals("Screen.title", violations[0].path)
    }

    @Test
    fun added_field_with_default_is_not_a_violation() {
        val old = snapshot(
            dataClasses = mapOf("Screen" to cls("id" to field("kotlin.String"))),
        )
        val new = snapshot(
            dataClasses = mapOf(
                "Screen" to cls(
                    "id" to field("kotlin.String"),
                    "title" to field("kotlin.String", optional = true),
                ),
            ),
        )
        assertEquals(emptyList(), lintProtocol(old, new))
    }

    @Test
    fun tightened_nullability_is_a_violation() {
        val old = snapshot(
            dataClasses = mapOf("X" to cls("name" to field("kotlin.String", nullable = true))),
        )
        val new = snapshot(
            dataClasses = mapOf("X" to cls("name" to field("kotlin.String", nullable = false))),
        )
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.TightenedNullability, violations[0].kind)
    }

    @Test
    fun changed_field_type_is_a_violation() {
        val old = snapshot(
            dataClasses = mapOf("X" to cls("n" to field("kotlin.Int"))),
        )
        val new = snapshot(
            dataClasses = mapOf("X" to cls("n" to field("kotlin.Long"))),
        )
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.ChangedFieldType, violations[0].kind)
    }

    @Test
    fun removed_enum_case_is_a_violation() {
        val old = snapshot(enums = mapOf("Spacing" to listOf("Sm", "Md", "Lg")))
        val new = snapshot(enums = mapOf("Spacing" to listOf("Sm", "Md")))
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.RemovedEnumCase, violations[0].kind)
        assertEquals("Spacing.Lg", violations[0].path)
    }

    @Test
    fun added_enum_case_is_not_a_violation() {
        val old = snapshot(enums = mapOf("Spacing" to listOf("Sm", "Md")))
        val new = snapshot(enums = mapOf("Spacing" to listOf("Sm", "Md", "Lg")))
        assertEquals(emptyList(), lintProtocol(old, new))
    }

    @Test
    fun removed_sealed_subtype_is_a_violation() {
        val old = snapshot(
            sealed = mapOf(
                "Action" to ProtocolSnapshot.SealedHierarchy(
                    discriminator = "type",
                    subtypes = mapOf(
                        "navigate" to cls(),
                        "submit" to cls(),
                    ),
                ),
            ),
        )
        val new = snapshot(
            sealed = mapOf(
                "Action" to ProtocolSnapshot.SealedHierarchy(
                    discriminator = "type",
                    subtypes = mapOf("navigate" to cls()),
                ),
            ),
        )
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.RemovedSubtype, violations[0].kind)
    }

    @Test
    fun added_sealed_subtype_is_not_a_violation() {
        val old = snapshot(
            sealed = mapOf(
                "Action" to ProtocolSnapshot.SealedHierarchy(
                    discriminator = "type",
                    subtypes = mapOf("navigate" to cls()),
                ),
            ),
        )
        val new = snapshot(
            sealed = mapOf(
                "Action" to ProtocolSnapshot.SealedHierarchy(
                    discriminator = "type",
                    subtypes = mapOf("navigate" to cls(), "submit" to cls()),
                ),
            ),
        )
        assertEquals(emptyList(), lintProtocol(old, new))
    }

    @Test
    fun changed_discriminator_is_a_violation() {
        val old = snapshot(
            sealed = mapOf(
                "Action" to ProtocolSnapshot.SealedHierarchy(
                    discriminator = "type",
                    subtypes = mapOf("navigate" to cls()),
                ),
            ),
        )
        val new = snapshot(
            sealed = mapOf(
                "Action" to ProtocolSnapshot.SealedHierarchy(
                    discriminator = "kind",
                    subtypes = mapOf("navigate" to cls()),
                ),
            ),
        )
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.ChangedDiscriminator, violations[0].kind)
    }

    @Test
    fun optional_tightened_to_required_is_a_violation() {
        val old = snapshot(
            dataClasses = mapOf("X" to cls("n" to field("kotlin.Int", optional = true))),
        )
        val new = snapshot(
            dataClasses = mapOf("X" to cls("n" to field("kotlin.Int", optional = false))),
        )
        val violations = lintProtocol(old, new)
        assertEquals(1, violations.size)
        assertEquals(ViolationKind.OptionalTightenedToRequired, violations[0].kind)
    }

    @Test
    fun linter_flags_a_real_break_in_the_live_protocol() {
        val current = captureProtocolSnapshot()

        // Baseline invariant the CI hook relies on: the live protocol compared to itself
        // is clean.
        assertTrue(
            lintProtocol(current, current).isEmpty(),
            "the committed live protocol snapshot must be internally consistent",
        )

        // The load-bearing assertion: mutate the *live* protocol with an actual breaking
        // change (drop a field from a real data class) and prove the linter catches it.
        // A green `verifyProtocolSnapshot` only means something if this direction bites.
        val victim = current.dataClasses.entries.first { it.value.fields.isNotEmpty() }
        val droppedField = victim.value.fields.keys.first()
        val broken = current.copy(
            dataClasses = current.dataClasses + (
                victim.key to victim.value.copy(
                    fields = victim.value.fields - droppedField,
                )
                ),
        )

        val violations = lintProtocol(current, broken)
        assertEquals(1, violations.size, "expected exactly one RemovedField violation")
        assertEquals(ViolationKind.RemovedField, violations[0].kind)
        assertEquals("${victim.key}.$droppedField", violations[0].path)
    }

    @Test
    fun reordered_enum_cases_are_not_a_violation() {
        // Enum evolution is membership-based: order carries no wire meaning, so shuffling
        // existing cases must stay clean. (Only *removing* a case is breaking.)
        val old = snapshot(enums = mapOf("Spacing" to listOf("Sm", "Md", "Lg")))
        val reordered = snapshot(enums = mapOf("Spacing" to listOf("Lg", "Sm", "Md")))
        assertEquals(emptyList(), lintProtocol(old, reordered))
    }
}
