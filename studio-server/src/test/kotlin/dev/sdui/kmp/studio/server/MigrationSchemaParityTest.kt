package dev.sdui.kmp.studio.server

import dev.sdui.kmp.studio.server.db.StudioTables
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards PRODUCTION_READINESS server-render: the production Flyway migration set under
 * `db/migrations/` must fully provision the schema, so a real Postgres deploy never depends on
 * runtime `SchemaUtils` auto-DDL. This is a real migrate-then-introspect check: it applies every
 * `V*.sql` migration in version order against a fresh H2 database in PostgreSQL-compatibility mode
 * (the same `MODE=PostgreSQL` engine [dev.sdui.kmp.studio.server.db.StudioDatabase] uses) and then
 * asserts, via JDBC metadata, that every table and column in the [StudioTables] source of truth
 * exists — catching any table or column added to Exposed but forgotten in a migration.
 */
class MigrationSchemaParityTest {

    @Test
    fun `flyway migration set provisions every table and column in the Exposed schema`() {
        val url = "jdbc:h2:mem:studio-migtest-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        DriverManager.getConnection(url).use { conn ->
            applyMigrations(conn)

            val expected: Map<String, Set<String>> = StudioTables.associate { table ->
                table.tableName.lowercase() to table.columns.map { it.name.lowercase() }.toSet()
            }
            val actual: Map<String, Set<String>> = introspectColumns(conn)

            val missingTables = expected.keys - actual.keys
            assertTrue(
                missingTables.isEmpty(),
                "Flyway migrations under db/migrations/ do not create these tables present in " +
                    "StudioTables: $missingTables. Add a V*.sql migration for them.",
            )

            for ((table, columns) in expected) {
                val actualColumns = actual.getValue(table)
                val missingColumns = columns - actualColumns
                assertTrue(
                    missingColumns.isEmpty(),
                    "Table `$table` is missing columns $missingColumns that the Exposed schema " +
                        "declares. Extend the migration set (additive-only) to cover them.",
                )
            }
        }
    }

    /**
     * Apply every `V<n>__*.sql` migration in ascending version order — the same ordering Flyway
     * uses. We execute the raw DDL rather than pulling in an in-process Flyway runner because
     * production applies these scripts out-of-process (the docker-compose `flyway` service), so
     * the durable contract under test is the SQL itself, not a particular runner.
     */
    private fun applyMigrations(conn: Connection) {
        val migrationsDir = locateMigrationsDir()
        val migrations = migrationsDir.listFiles { f -> f.name.matches(MIGRATION_NAME) }
            ?.sortedBy { versionOf(it.name) }
            ?: error("No migration files found under ${migrationsDir.absolutePath}")

        assertTrue(migrations.isNotEmpty(), "Expected at least one migration under ${migrationsDir.absolutePath}")

        for (migration in migrations) {
            for (statement in statementsIn(migration)) {
                conn.createStatement().use { it.execute(statement) }
            }
        }
    }

    private fun statementsIn(migration: File): List<String> =
        migration.readText()
            .lineSequence()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun introspectColumns(conn: Connection): Map<String, MutableSet<String>> {
        val result = HashMap<String, MutableSet<String>>()
        conn.metaData.getColumns(null, null, null, null).use { rs ->
            while (rs.next()) {
                val table = rs.getString("TABLE_NAME").lowercase()
                val column = rs.getString("COLUMN_NAME").lowercase()
                result.getOrPut(table) { mutableSetOf() }.add(column)
            }
        }
        return result
    }

    private fun locateMigrationsDir(): File {
        // Gradle runs the test task with the module directory as its working directory, so the
        // committed migrations live at `db/migrations`. Fall back to a couple of nearby roots so
        // the test also works when driven from the repo root.
        val candidates = listOf(
            File("db/migrations"),
            File("studio-server/db/migrations"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate db/migrations from ${File(".").absolutePath}")
    }

    private fun versionOf(name: String): Int =
        MIGRATION_NAME.matchEntire(name)?.groupValues?.get(1)?.toInt()
            ?: error("Migration filename does not match V<n>__*.sql: $name")

    private companion object {
        val MIGRATION_NAME = Regex("""V(\d+)__.*\.sql""")
    }
}
