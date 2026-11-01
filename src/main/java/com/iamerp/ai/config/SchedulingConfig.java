package com.iamerp.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Scheduled support (used by DealerNotifyPoller's 5-minute poll).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
