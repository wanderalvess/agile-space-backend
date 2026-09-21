package com.agilespace.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita @Scheduled em toda a aplicação — hoje só SquadSyncScheduler usa isso, e mesmo
 * esse fica inerte a menos que app.squad.scheduled-sync.enabled esteja true (ver
 * application.yml). Nenhuma dependência Maven nova: spring-context (transitivo via
 * spring-boot-starter-web) já traz tudo que @Scheduled precisa.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
