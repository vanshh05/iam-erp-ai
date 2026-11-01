package com.iamerp.ai.functions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net poll for dealer notifications. The ERP's poke endpoint delivers
 * pushes instantly; this catches anything a missed poke left behind. Runs every
 * 5 minutes by default (override with DEALER_NOTIFY_POLL_MS).
 */
@Component
public class DealerNotifyPoller {

    private static final Logger log = LoggerFactory.getLogger(DealerNotifyPoller.class);

    private final DealerNotifyFunctions notify;

    public DealerNotifyPoller(DealerNotifyFunctions notify) {
        this.notify = notify;
    }

    @Scheduled(fixedDelayString = "${DEALER_NOTIFY_POLL_MS:300000}", initialDelay = 60000)
    public void poll() {
        try {
            int pushed = notify.process();
            if (pushed > 0) log.info("[DealerNotify] poll pushed {} notification(s)", pushed);
        } catch (Exception e) {
            log.error("[DealerNotify] poll error: {}", e.getMessage());
        }
    }
}
