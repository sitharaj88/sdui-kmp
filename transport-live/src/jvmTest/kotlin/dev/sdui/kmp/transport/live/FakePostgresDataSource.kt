package dev.sdui.kmp.transport.live

import org.postgresql.PGConnection
import org.postgresql.PGNotification
import java.io.PrintWriter
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * In-process fake of a Postgres `DataSource` whose connections honour the subset of
 * `LISTEN sdui_live` / `NOTIFY sdui_live, '...'` semantics that [PostgresLivePublisher]
 * exercises. Every fake [Connection] is unwrappable to a [PGConnection] and presents a
 * private notification queue that the test drains by issuing fake NOTIFY statements
 * against a sibling connection.
 *
 * Not a generic Postgres simulator — only [Statement.execute] is implemented, only the
 * exact `LISTEN sdui_live` and `NOTIFY sdui_live, '...'` shapes are recognised, and only
 * `getNotifications(Int)` is honoured on the [PGConnection] surface. Everything else
 * throws.
 *
 * **Failure injection.** [failOpenAfter] makes the next N `getConnection` calls succeed,
 * then throws [SQLException] until [recover] flips it back. Used to assert the listener
 * loop's reconnect+backoff path.
 */
internal class FakePostgresDataSource(
    private val failOpenAfter: AtomicInteger = AtomicInteger(Int.MAX_VALUE),
) : DataSource {

    /** Every fake connection that has run `LISTEN sdui_live` and is therefore notify-eligible. */
    private val listeners = ConcurrentLinkedQueue<FakeConnection>()
    private val openConnections = ConcurrentLinkedQueue<FakeConnection>()
    private val openCount = AtomicInteger(0)

    /** Diagnostic accessor — number of currently-open dedicated listener connections. */
    fun openListenerCount(): Int = listeners.count { !it.closedRef.get() }

    /** Total `getConnection` calls since construction, including the ones that failed. */
    fun connectionAttempts(): Int = openCount.get()

    /** Reset failure injection. */
    fun recover() {
        failOpenAfter.set(Int.MAX_VALUE)
    }

    /** Next [count] `getConnection` calls succeed, then start failing. */
    fun failAfter(count: Int) {
        failOpenAfter.set(count)
    }

    /** Force-close every open connection (simulates a server-side `pg_terminate_backend`). */
    fun killAllConnections() {
        openConnections.forEach { it.kill() }
    }

    override fun getConnection(): Connection {
        val remaining = failOpenAfter.getAndUpdate { if (it > 0) it - 1 else 0 }
        openCount.incrementAndGet()
        if (remaining <= 0) throw SQLException("simulated connect failure")
        val conn = FakeConnection(this)
        openConnections += conn
        return conn
    }

    override fun getConnection(username: String?, password: String?): Connection = getConnection()

    /** Fan a NOTIFY out to every listener connection. */
    fun deliverNotify(parameter: String) {
        val live = listeners.filter { !it.closedRef.get() }
        live.forEach { it.enqueue(parameter) }
    }

    fun registerListener(connection: FakeConnection) {
        listeners += connection
    }

    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getLogger("FakePostgresDataSource")
    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

/**
 * Minimal Connection that recognises:
 *  - `LISTEN sdui_live` → registers this connection with the parent fake.
 *  - `NOTIFY sdui_live, '<payload>'` → fans out the payload to every listener.
 *
 * Unwraps to a [FakePGConnection] for `getNotifications`.
 */
internal class FakeConnection(private val parent: FakePostgresDataSource) : Connection {
    val closedRef = AtomicBoolean(false)
    private val notifications = java.util.concurrent.LinkedBlockingQueue<PGNotification>()
    private val pgFacade = FakePGConnection(notifications)

    fun kill() {
        closedRef.set(true)
    }

    fun enqueue(parameter: String) {
        notifications += FakePGNotification(parameter)
    }

    override fun close() {
        closedRef.set(true)
    }

    override fun isClosed(): Boolean = closedRef.get()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        if (iface != null && iface.isAssignableFrom(PGConnection::class.java)) {
            return pgFacade as T
        }
        error("FakeConnection.unwrap($iface) not implemented")
    }

    override fun isWrapperFor(iface: Class<*>?): Boolean =
        iface != null && iface.isAssignableFrom(PGConnection::class.java)

    override fun createStatement(): Statement = FakeStatement(this, parent)

    // Everything else: minimal not-implemented stubs. The publisher only ever calls
    // close / isClosed / unwrap / createStatement / autoCommit getters and setters.
    private var auto = true
    override fun setAutoCommit(autoCommit: Boolean) {
        auto = autoCommit
    }
    override fun getAutoCommit(): Boolean = auto
    override fun commit() = Unit
    override fun rollback() = Unit
    override fun getMetaData() = error("not used")
    override fun setReadOnly(readOnly: Boolean) = Unit
    override fun isReadOnly(): Boolean = false
    override fun setCatalog(catalog: String?) = Unit
    override fun getCatalog(): String? = null
    override fun setTransactionIsolation(level: Int) = Unit
    override fun getTransactionIsolation(): Int = 0
    override fun getWarnings() = null
    override fun clearWarnings() = Unit
    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement =
        createStatement()
    override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement =
        error("not used")
    override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int) = error("not used")
    override fun getTypeMap() = error("not used")
    override fun setTypeMap(map: MutableMap<String, Class<*>>?) = Unit
    override fun setHoldability(holdability: Int) = Unit
    override fun getHoldability(): Int = 0
    override fun setSavepoint() = error("not used")
    override fun setSavepoint(name: String?) = error("not used")
    override fun rollback(savepoint: java.sql.Savepoint?) = Unit
    override fun releaseSavepoint(savepoint: java.sql.Savepoint?) = Unit
    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement =
        createStatement()
    override fun prepareStatement(
        sql: String?,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): PreparedStatement = error("not used")
    override fun prepareCall(
        sql: String?,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ) = error("not used")
    override fun prepareStatement(sql: String?, autoGeneratedKeys: Int): PreparedStatement = error("not used")
    override fun prepareStatement(sql: String?, columnIndexes: IntArray?): PreparedStatement = error("not used")
    override fun prepareStatement(sql: String?, columnNames: Array<out String>?): PreparedStatement = error("not used")
    override fun prepareStatement(sql: String?): PreparedStatement = error("not used")
    override fun prepareCall(sql: String?) = error("not used")
    override fun nativeSQL(sql: String?): String = sql.orEmpty()
    override fun createClob() = error("not used")
    override fun createBlob() = error("not used")
    override fun createNClob() = error("not used")
    override fun createSQLXML() = error("not used")
    override fun isValid(timeout: Int): Boolean = !closedRef.get()
    override fun setClientInfo(name: String?, value: String?) = Unit
    override fun setClientInfo(properties: java.util.Properties?) = Unit
    override fun getClientInfo(name: String?): String? = null
    override fun getClientInfo(): java.util.Properties = java.util.Properties()
    override fun createArrayOf(typeName: String?, elements: Array<out Any>?) = error("not used")
    override fun createStruct(typeName: String?, attributes: Array<out Any>?) = error("not used")
    override fun setSchema(schema: String?) = Unit
    override fun getSchema(): String? = null
    override fun abort(executor: java.util.concurrent.Executor?) = Unit
    override fun setNetworkTimeout(executor: java.util.concurrent.Executor?, milliseconds: Int) = Unit
    override fun getNetworkTimeout(): Int = 0
}

/**
 * Statement that recognises just the two `LISTEN` / `NOTIFY` shapes used by
 * [PostgresLivePublisher]. Anything else throws so an accidental real-SQL escape is loud.
 */
internal class FakeStatement(
    private val connection: FakeConnection,
    private val parent: FakePostgresDataSource,
) : Statement {

    private val notifyRegex = Regex("""^\s*NOTIFY\s+sdui_live\s*,\s*'(.*)'\s*$""", RegexOption.IGNORE_CASE)

    override fun execute(sql: String?): Boolean {
        val q = sql?.trim().orEmpty()
        return when {
            q.equals("LISTEN sdui_live", ignoreCase = true) -> {
                parent.registerListener(connection)
                false
            }
            q.startsWith("NOTIFY", ignoreCase = true) -> {
                val match = notifyRegex.matchEntire(q) ?: error("malformed NOTIFY: $q")
                // Un-escape doubled single-quotes that PostgresLivePublisher emits.
                val payload = match.groupValues[1].replace("''", "'")
                parent.deliverNotify(payload)
                false
            }
            else -> error("FakeStatement does not implement: $q")
        }
    }

    override fun close() = Unit

    // Trivial / unused. We have to override the whole Statement surface — pgjdbc's real
    // class is huge but [PostgresLivePublisher] only ever calls execute(String) and close().
    override fun executeQuery(sql: String?): ResultSet = error("not used")
    override fun executeUpdate(sql: String?): Int = 0
    override fun getMaxFieldSize(): Int = 0
    override fun setMaxFieldSize(max: Int) = Unit
    override fun getMaxRows(): Int = 0
    override fun setMaxRows(max: Int) = Unit
    override fun setEscapeProcessing(enable: Boolean) = Unit
    override fun getQueryTimeout(): Int = 0
    override fun setQueryTimeout(seconds: Int) = Unit
    override fun cancel() = Unit
    override fun getWarnings() = null
    override fun clearWarnings() = Unit
    override fun setCursorName(name: String?) = Unit
    override fun getResultSet(): ResultSet? = null
    override fun getUpdateCount(): Int = -1
    override fun getMoreResults(): Boolean = false
    override fun setFetchDirection(direction: Int) = Unit
    override fun getFetchDirection(): Int = 0
    override fun setFetchSize(rows: Int) = Unit
    override fun getFetchSize(): Int = 0
    override fun getResultSetConcurrency(): Int = 0
    override fun getResultSetType(): Int = 0
    override fun addBatch(sql: String?) = Unit
    override fun clearBatch() = Unit
    override fun executeBatch(): IntArray = IntArray(0)
    override fun getConnection(): Connection = connection
    override fun getMoreResults(current: Int): Boolean = false
    override fun getGeneratedKeys(): ResultSet = error("not used")
    override fun executeUpdate(sql: String?, autoGeneratedKeys: Int): Int = 0
    override fun executeUpdate(sql: String?, columnIndexes: IntArray?): Int = 0
    override fun executeUpdate(sql: String?, columnNames: Array<out String>?): Int = 0
    override fun execute(sql: String?, autoGeneratedKeys: Int): Boolean = execute(sql)
    override fun execute(sql: String?, columnIndexes: IntArray?): Boolean = execute(sql)
    override fun execute(sql: String?, columnNames: Array<out String>?): Boolean = execute(sql)
    override fun getResultSetHoldability(): Int = 0
    override fun isClosed(): Boolean = false
    override fun setPoolable(poolable: Boolean) = Unit
    override fun isPoolable(): Boolean = false
    override fun closeOnCompletion() = Unit
    override fun isCloseOnCompletion(): Boolean = false
    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

/** PGConnection facade — only `getNotifications(Int)` is functional. */
internal class FakePGConnection(
    private val queue: java.util.concurrent.BlockingQueue<PGNotification>,
) : PGConnection {

    override fun getNotifications(): Array<PGNotification>? = getNotifications(0)

    override fun getNotifications(timeoutMillis: Int): Array<PGNotification>? {
        // Blocking poll; if the timeout is 0, peek immediately.
        val first = if (timeoutMillis <= 0) {
            queue.poll() ?: return emptyArray()
        } else {
            queue.poll(timeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return emptyArray()
        }
        val drained = mutableListOf(first)
        queue.drainTo(drained)
        return drained.toTypedArray()
    }

    override fun createArrayOf(typeName: String?, elements: Any?): java.sql.Array = error("not used")
    override fun getCopyAPI() = error("not used")
    override fun getLargeObjectAPI() = error("not used")
    override fun getFastpathAPI() = error("not used")
    override fun addDataType(type: String?, klass: String?) = Unit
    override fun addDataType(type: String?, klass: Class<out org.postgresql.util.PGobject>?) = Unit
    override fun setPrepareThreshold(threshold: Int) = Unit
    override fun getPrepareThreshold(): Int = 0
    override fun setDefaultFetchSize(fetchSize: Int) = Unit
    override fun getDefaultFetchSize(): Int = 0
    override fun getBackendPID(): Int = 1
    override fun cancelQuery() = Unit
    override fun escapeIdentifier(identifier: String?): String = identifier.orEmpty()
    override fun escapeLiteral(literal: String?): String = literal.orEmpty()
    override fun getPreferQueryMode() = error("not used")
    override fun getAutosave() = error("not used")
    override fun setAutosave(autosave: org.postgresql.jdbc.AutoSave?) = Unit
    override fun getReplicationAPI() = error("not used")
    override fun getParameterStatuses(): MutableMap<String, String> = mutableMapOf()
    override fun getParameterStatus(parameterName: String?): String? = null
    override fun setAdaptiveFetch(adaptiveFetch: Boolean) = Unit
    override fun getAdaptiveFetch(): Boolean = false
}

internal class FakePGNotification(private val parameter: String) : PGNotification {
    override fun getName(): String = PostgresLivePublisher.CHANNEL
    override fun getPID(): Int = 1
    override fun getParameter(): String = parameter
}
