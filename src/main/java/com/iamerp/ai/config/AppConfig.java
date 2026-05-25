package com.iamerp.ai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

@Configuration
public class AppConfig {

    // ─── Supabase datasource (primary - AI tables) ───────────────────────────

    @Value("${spring.datasource.url}")
    private String supabaseUrl;

    @Value("${spring.datasource.username}")
    private String supabaseUsername;

    @Value("${spring.datasource.password}")
    private String supabasePassword;

    @Bean(name = "supabaseDataSource")
    @Primary
    public DataSource supabaseDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(supabaseUrl);
        config.setUsername(supabaseUsername);
        config.setPassword(supabasePassword);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setPoolName("SupabasePool");
        return new HikariDataSource(config);
    }

    // ─── Main ERP datasource (DISABLED until GCP is ready) ───────────────────
    // Uncomment this entire block when you are ready to connect your GCP database.
    //
    // @Value("${erp.datasource.url}")
    // private String erpUrl;
    //
    // @Value("${erp.datasource.username}")
    // private String erpUsername;
    //
    // @Value("${erp.datasource.password}")
    // private String erpPassword;
    //
    // @Bean(name = "erpDataSource")
    // public DataSource erpDataSource() {
    //     HikariConfig config = new HikariConfig();
    //     config.setJdbcUrl(erpUrl);
    //     config.setUsername(erpUsername);
    //     config.setPassword(erpPassword);
    //     config.setDriverClassName("org.postgresql.Driver");
    //     config.setMaximumPoolSize(3);
    //     config.setMinimumIdle(1);
    //     config.setConnectionTimeout(30000);
    //     config.setPoolName("ErpPool");
    //     return new HikariDataSource(config);
    // }

    // ─── CORS configuration ───────────────────────────────────────────────────

    @Value("${cors.allowed.origins}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .maxAge(3600);
            }
        };
    }
}
