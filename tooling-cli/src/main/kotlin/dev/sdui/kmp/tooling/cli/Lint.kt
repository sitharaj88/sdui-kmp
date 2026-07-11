package dev.sdui.kmp.tooling.cli

/** Categories of breaking change the schema linter enforces. */
public enum class ViolationKind {
    RemovedType,
    RemovedSubtype,
    RemovedField,
    RemovedEnumCase,
    ChangedFieldType,
    TightenedNullability,
    ChangedDiscriminator,
    OptionalTightenedToRequired,
}

/** One breaking change found by the linter. */
public data class Violation(
    public val kind: ViolationKind,
    public val path: String,
    public val message: String,
)

/**
 * Compares [baseline] to [current] and returns every breaking change.
 *
 * Additive changes — new types, new fields with defaults, new enum cases, new subtypes —
 * produce no violations. This is the concrete enforcement of
 * [the "additive-only evolution" non-negotiable](../../../../../../../VISION.md).
 */
public fun lintProtocol(baseline: ProtocolSnapshot, current: ProtocolSnapshot): List<Violation> {
    val out = mutableListOf<Violation>()

    // Sealed hierarchies
    for ((name, oldHier) in baseline.sealedHierarchies) {
        val newHier = current.sealedHierarchies[name]
        if (newHier == null) {
            out += Violation(ViolationKind.RemovedType, name, "sealed hierarchy '$name' was removed")
            continue
        }
        if (oldHier.discriminator != newHier.discriminator) {
            out += Violation(
                ViolationKind.ChangedDiscriminator,
                name,
                "sealed '$name' discriminator changed from '${oldHier.discriminator}' to '${newHier.discriminator}'",
            )
        }
        for ((subName, oldSub) in oldHier.subtypes) {
            val newSub = newHier.subtypes[subName]
            if (newSub == null) {
                out += Violation(
                    ViolationKind.RemovedSubtype,
                    "$name.$subName",
                    "sealed '$name' subtype '$subName' was removed",
                )
                continue
            }
            out += diffFields(oldSub, newSub, "$name.$subName")
        }
    }

    // Enums
    for ((name, oldCases) in baseline.enums) {
        val newCases = current.enums[name]
        if (newCases == null) {
            out += Violation(ViolationKind.RemovedType, name, "enum '$name' was removed")
            continue
        }
        for (case in oldCases) {
            if (case !in newCases) {
                out += Violation(
                    ViolationKind.RemovedEnumCase,
                    "$name.$case",
                    "enum '$name' case '$case' was removed",
                )
            }
        }
    }

    // Data classes
    for ((name, oldShape) in baseline.dataClasses) {
        val newShape = current.dataClasses[name]
        if (newShape == null) {
            out += Violation(ViolationKind.RemovedType, name, "data class '$name' was removed")
            continue
        }
        out += diffFields(oldShape, newShape, name)
    }

    return out
}

private fun diffFields(
    old: ProtocolSnapshot.DataClassShape,
    new: ProtocolSnapshot.DataClassShape,
    path: String,
): List<Violation> {
    val out = mutableListOf<Violation>()
    for ((fieldName, oldField) in old.fields) {
        val newField = new.fields[fieldName]
        if (newField == null) {
            out += Violation(ViolationKind.RemovedField, "$path.$fieldName", "field was removed")
            continue
        }
        if (oldField.type != newField.type) {
            out += Violation(
                ViolationKind.ChangedFieldType,
                "$path.$fieldName",
                "type changed from '${oldField.type}' to '${newField.type}'",
            )
        }
        if (oldField.nullable && !newField.nullable) {
            out += Violation(
                ViolationKind.TightenedNullability,
                "$path.$fieldName",
                "nullable tightened to non-nullable",
            )
        }
        if (oldField.optional && !newField.optional) {
            out += Violation(
                ViolationKind.OptionalTightenedToRequired,
                "$path.$fieldName",
                "optional field became required (no default)",
            )
        }
    }
    return out
}
