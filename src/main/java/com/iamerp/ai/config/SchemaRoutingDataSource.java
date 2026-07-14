package com.iamerp.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps the real (pooled) DataSource and points every borrowed connection at
 * the correct schema:
 *
 *   - PRIMARY schema by default (auth, admin, and all normal reads).
 *   - BACKUP schema when the requesting company's is_data_refresh flag is 1
 *     in ai_data_refresh_status — i.e. while the client-side sync job is
 *     deleting/re-inserting that company's data in the primary schema.
 *
 * Because the switch happens at the connection level (MySQL USE via
 * setCatalog), every query in the app — ErpFunctions, StockFunctions,
 * DispatchFunctions, ExportController — routes correctly with ZERO changes
 * to any SQL.
 *
 * The catalog is set EXPLICITLY on every borrow (never inherited from pool
 * state), so a connection previously switched to the backup schema can never
 * leak into an auth or admin query.
 */
public class SchemaRoutingDataSource implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(SchemaRoutingDataSource.class);

    private final DataSource delegate;
    private final String primarySchema;
    private final String backupSchema;

    /** Small TTL cache so a burst of tool calls in one chat request doesn't
     *  re-query the flag every time. The refresh window is minutes long, so a
     *  few seconds of staleness is harmless. */
    private static final long FLAG_TTL_MS = 3000;
    private final Map<String, long[]> flagCache = new ConcurrentHashMap<>(); // company -> [flag, expiresAt]

    public SchemaRoutingDataSource(DataSource delegate, String primarySchema, String backupSchema) {
        this.delegate = delegate;
        this.primarySchema = primarySchema;
        this.backupSchema = backupSchema;
        log.info("[SchemaRouting] primary='{}' backup='{}'", primarySchema, backupSchema);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return route(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return route(delegate.getConnection(username, password));
    }

    private Connection route(Connection conn) throws SQLException {
        // Deterministic baseline on EVERY borrow — pooled connections may carry
        // whatever catalog the previous borrower left behind.
        conn.setCatalog(primarySchema);

        String company = CompanyContext.get();
        if (company != null && isRefreshing(conn, company)) {
            conn.setCatalog(backupSchema);
        }
        return conn;
    }

    private boolean isRefreshing(Connection conn, String company) {
        long now = System.currentTimeMillis();
        long[] cached = flagCache.get(company);
        if (cached != null && cached[1] > now) {
            return cached[0] == 1;
        }
        long flag = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_data_refresh FROM ai_data_refresh_status WHERE company_id = ?")) {
            ps.setString(1, company);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) flag = rs.getInt(1) == 1 ? 1 : 0;
                // no row for this company -> not refreshing -> primary
            }
        } catch (SQLException e) {
            // If the flag check itself fails, fail SAFE to the primary schema
            // rather than blocking the query.
            log.warn("[SchemaRouting] flag check failed for company '{}': {} — using primary", company, e.getMessage());
            flag = 0;
        }
        if (flag == 1) {
            log.info("[SchemaRouting] company '{}' is refreshing — routing to '{}'", company, backupSchema);
        }
        flagCache.put(company, new long[]{flag, now + FLAG_TTL_MS});
        return flag == 1;
    }

    // ─── plumbing: delegate everything else ──────────────────────────────────

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
