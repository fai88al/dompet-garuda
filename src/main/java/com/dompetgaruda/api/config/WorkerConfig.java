package com.dompetgaruda.api.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker-profile Spring configuration.
 *
 * <p>Enables task scheduling and wires ShedLock's Postgres-backed {@link LockProvider}.
 * Every {@code @Scheduled} method in the worker must also carry {@code @SchedulerLock}
 * (§7 invariant 7 in CLAUDE.md).
 *
 * <p>{@code @Profile("worker")} ensures these beans never load in the api container,
 * preventing any accidental scheduling on the REST-serving process.
 */
@Configuration
@Profile("worker")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class WorkerConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build());
    }
}
