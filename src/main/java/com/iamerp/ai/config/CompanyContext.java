package com.iamerp.ai.config;

/**
 * Holds the company for the CURRENT request thread so the schema-routing
 * DataSource can decide (per company) whether to serve reads from the
 * primary schema or the backup schema while that company's data refresh
 * job is running.
 *
 * Controllers set this after resolving the company and MUST clear it in a
 * finally block. Any code path that never sets it (auth, admin CRUD,
 * password reset) automatically stays on the primary schema.
 */
public final class CompanyContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CompanyContext() {}

    public static void set(String companyId) {
        CURRENT.set(companyId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
