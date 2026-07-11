package dev.sdui.kmp.studio.server.db

/**
 * Authorization roles for Studio editors. Stored on [EditorAccounts.role] as the lowercase
 * literal — kept in lockstep with the SQL `VARCHAR(16)` column.
 *
 * Role hierarchy (least to most permissive):
 *  - [Viewer] — read-only access to screens, versions, audit.
 *  - [Editor] — viewer + draft + publish.
 *  - [Admin]  — editor + revert + delete + (eventually) account management.
 */
public enum class EditorRole(public val wire: String) {
    Viewer("viewer"),
    Editor("editor"),
    Admin("admin");

    /** Whether this role grants at least the same permissions as [other]. */
    public fun grants(other: EditorRole): Boolean = ordinal >= other.ordinal

    public companion object {
        /** Parse a wire value, defaulting to [Viewer] for unknown strings (defence-in-depth). */
        public fun parse(value: String): EditorRole = when (value.lowercase()) {
            Admin.wire -> Admin
            Editor.wire -> Editor
            else -> Viewer
        }
    }
}
