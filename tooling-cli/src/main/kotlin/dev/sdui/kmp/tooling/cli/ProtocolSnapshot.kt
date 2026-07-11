package dev.sdui.kmp.tooling.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Captures the structural shape of a protocol version.
 *
 * The [version] field is the snapshot schema version itself — bump when this type changes
 * incompatibly. It is independent of [dev.sdui.kmp.protocol.SchemaVersion] (the wire
 * protocol's own version).
 *
 * Maps are serialized in whatever order they were inserted in; [captureProtocolSnapshot]
 * sorts everything alphabetically so the baseline `protocol-snapshot.json` committed at
 * repo root has a deterministic textual diff under code review.
 */
@Serializable
public data class ProtocolSnapshot(
    public val version: Int = 1,
    public val sealedHierarchies: Map<String, SealedHierarchy> = emptyMap(),
    public val enums: Map<String, List<String>> = emptyMap(),
    public val dataClasses: Map<String, DataClassShape> = emptyMap(),
) {
    @Serializable
    public data class SealedHierarchy(
        public val discriminator: String,
        public val subtypes: Map<String, DataClassShape>,
    )

    @Serializable
    public data class DataClassShape(
        public val fields: Map<String, FieldShape>,
    )

    @Serializable
    public data class FieldShape(
        public val type: String,
        public val nullable: Boolean,
        public val optional: Boolean,
    )

    public companion object {
        public val Json: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
    }
}
