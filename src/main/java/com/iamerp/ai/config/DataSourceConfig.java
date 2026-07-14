package com.iamerp.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Replaces the auto-configured DataSource with the schema-routing wrapper.
 * Everything (JPA repositories, ErpFunctions, StockFunctions,
 * DispatchFunctions, ExportController) receives the wrapper, so schema
 * failover during data-refresh windows applies everywhere automatically.
 *
 * Schema names come from environment variables so they can be corrected on
 * Render without a code change:
 *   DB_PRIMARY_SCHEMA (default: ai_ayaam)   — MUST match the schema in the JDBC URL exactly
 *   DB_BACKUP_SCHEMA  (default: ai_ayaam_backup)
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties,
                                 @Value("${DB_PRIMARY_SCHEMA:ai_ayaam}") String primarySchema,
                                 @Value("${DB_BACKUP_SCHEMA:ai_ayaam_backup}") String backupSchema) {
        HikariDataSource hikari = properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new SchemaRoutingDataSource(hikari, primarySchema, backupSchema);
    }
}
