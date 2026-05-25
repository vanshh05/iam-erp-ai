package com.iamerp.ai.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * Sync job — DISABLED until GCP database is ready.
 *
 * When you are ready to connect GCP:
 * 1. Uncomment erpDataSource in AppConfig.java
 * 2. Uncomment erpDataSource fields and constructor param below
 * 3. Replace all TODO queries with your actual ERP table/column names
 * 4. Set sync.enabled=true in application.properties
 */
@Service
public class SyncJob {

    private static final Logger log = LoggerFactory.getLogger(SyncJob.class);

    private final DataSource supabaseDataSource;

    // Uncomment when GCP is ready:
    // private final DataSource erpDataSource;

    @Value("${sync.enabled:false}")
    private boolean syncEnabled;

    public SyncJob(@Qualifier("supabaseDataSource") DataSource supabaseDataSource) {
        this.supabaseDataSource = supabaseDataSource;
        // this.erpDataSource = erpDataSource;
    }

    // ─── Main sync trigger (every 2 minutes) ─────────────────────────────────

    @Scheduled(cron = "${sync.cron:0 */2 * * * *}")
    public void runSync() {
        if (!syncEnabled) {
            log.debug("Sync is disabled. Enable when GCP database is connected.");
            return;
        }

        log.info("Sync started at {}", LocalDateTime.now());
        long start = System.currentTimeMillis();

        try {
            syncStock();
            syncPartyStatus();
            syncSales();
            syncDailySummary();
            syncLedgerSummary();
            syncCollections();
            syncStockMovements();

            long elapsed = System.currentTimeMillis() - start;
            log.info("Sync completed in {}ms", elapsed);

        } catch (Exception e) {
            log.error("Sync failed: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All sync methods below are stubs until GCP is connected.
    // When ready, inject erpDataSource and replace TODO queries.
    // ─────────────────────────────────────────────────────────────────────────

    private void syncStock() {
        // TODO: Replace with actual ERP stock table query when GCP is connected.
        // Expected columns: item_name, category, sub_category, current_qty,
        //                   unit, min_stock_level, purchase_rate, selling_rate
        log.debug("syncStock() — waiting for GCP connection");
    }

    private void syncPartyStatus() {
        // TODO: Replace with actual ERP party/ledger master query.
        // Expected columns: party_name, party_type, contact_number,
        //                   outstanding_balance, balance_type (DR/CR),
        //                   last_bill_date, last_bill_amount,
        //                   total_business_ytd, credit_limit, overdue_days
        log.debug("syncPartyStatus() — waiting for GCP connection");
    }

    private void syncSales() {
        // TODO: Replace with actual ERP billing/sales table query.
        // Expected columns: bill_no, bill_date, bill_time, party_name,
        //                   gross_amount, discount_amount, tax_amount,
        //                   net_amount, payment_mode, created_by, branch
        log.debug("syncSales() — waiting for GCP connection");
    }

    private void syncDailySummary() {
        // TODO: Aggregate from your ERP billing table grouped by date.
        // Expected columns: summary_date, total_sales, total_bills_count,
        //                   cash_sales, credit_sales, total_discount_given,
        //                   total_tax_collected
        log.debug("syncDailySummary() — waiting for GCP connection");
    }

    private void syncLedgerSummary() {
        // TODO: Replace with your ERP ledger summary table.
        // Expected columns: party_name, period_from, period_to,
        //                   opening_balance, total_debit, total_credit,
        //                   closing_balance, balance_type
        log.debug("syncLedgerSummary() — waiting for GCP connection");
    }

    private void syncCollections() {
        // TODO: Replace with your ERP receipts/collections table.
        // Expected columns: receipt_no, receipt_date, party_name, amount,
        //                   payment_mode, bank_name, cheque_no, created_by
        log.debug("syncCollections() — waiting for GCP connection");
    }

    private void syncStockMovements() {
        // TODO: Replace with your ERP stock movement/transaction table.
        // Expected columns: movement_id, movement_date, item_name,
        //                   movement_type (IN/OUT/ADJUST), qty,
        //                   reference_no, party_name, rate
        log.debug("syncStockMovements() — waiting for GCP connection");
    }
}
