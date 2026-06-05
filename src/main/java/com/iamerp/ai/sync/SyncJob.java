package com.iamerp.ai.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sync job — DISABLED until GCP ERP tables are ready.
 * When ready: inject DataSource, replace TODO queries with actual ERP table names.
 */
@Service
public class SyncJob {

    private static final Logger log = LoggerFactory.getLogger(SyncJob.class);

    @Value("${sync.enabled:false}")
    private boolean syncEnabled;

    @Scheduled(cron = "${sync.cron:0 */2 * * * *}")
    public void runSync() {
        if (!syncEnabled) {
            log.debug("Sync is disabled.");
            return;
        }
        log.info("Sync job triggered — implement queries when ERP tables are ready.");
    }
}