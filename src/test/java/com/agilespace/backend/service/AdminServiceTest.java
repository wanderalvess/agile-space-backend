package com.agilespace.backend.service;

import com.agilespace.backend.domain.AuditLog;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.SystemConfig;
import com.agilespace.backend.repository.AuditLogRepository;
import com.agilespace.backend.repository.GlobalAnnouncementRepository;
import com.agilespace.backend.repository.SystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService - Configurações Globais, Comunicados, Auditoria e Estatísticas do Sistema")
class AdminServiceTest {

    @Mock
    private SystemConfigRepository configRepository;

    @Mock
    private GlobalAnnouncementRepository announcementRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdminService service;

    @Nested
    @DisplayName("Configurações Globais do Sistema")
    class SystemConfigTests {

        @Test
        @DisplayName("Deve buscar valor de configuração existente")
        void shouldGetConfigValue() {
            SystemConfig config = SystemConfig.builder().key("health_check_ping").value("active").build();
            when(configRepository.findById("health_check_ping")).thenReturn(Optional.of(config));

            String value = service.getConfig("health_check_ping");

            assertEquals("active", value);
            verify(configRepository).findById("health_check_ping");
        }

        @Test
        @DisplayName("Deve retornar null quando chave de configuração não existir")
        void shouldReturnNullWhenConfigNotFound() {
            when(configRepository.findById("unknown")).thenReturn(Optional.empty());

            assertNull(service.getConfig("unknown"));
        }

        @Test
        @DisplayName("Deve salvar ou atualizar chave de configuração")
        void shouldSetConfig() {
            SystemConfig config = SystemConfig.builder().key("maintenance_mode").value("true").build();
            when(configRepository.save(any(SystemConfig.class))).thenReturn(config);

            service.setConfig("maintenance_mode", "true");

            verify(configRepository).save(any(SystemConfig.class));
        }
    }

    @Nested
    @DisplayName("Comunicados Globais (Announcements)")
    class AnnouncementTests {

        @Test
        @DisplayName("Deve listar comunicados ordenados pela data de criação decrescente")
        void shouldGetAnnouncements() {
            when(announcementRepository.findByOrderByCreatedAtDesc())
                    .thenReturn(Collections.singletonList(new GlobalAnnouncement()));

            List<GlobalAnnouncement> list = service.getAnnouncements();

            assertEquals(1, list.size());
            verify(announcementRepository).findByOrderByCreatedAtDesc();
        }

        @Test
        @DisplayName("Deve criar comunicado gerando ID e data de publicação")
        void shouldCreateAnnouncementWithGeneratedMetadata() {
            GlobalAnnouncement announcement = GlobalAnnouncement.builder()
                    .title("Manutenção Programada")
                    .content("Atualização de segurança às 22h")
                    .createdBy("admin")
                    .build();

            when(announcementRepository.save(any(GlobalAnnouncement.class))).thenAnswer(i -> i.getArgument(0));

            GlobalAnnouncement saved = service.createAnnouncement(announcement);

            assertNotNull(saved.getId());
            assertNotNull(saved.getCreatedAt());
            assertEquals("Manutenção Programada", saved.getTitle());
            verify(announcementRepository).save(announcement);
        }

        @Test
        @DisplayName("Deve excluir comunicado pelo identificador")
        void shouldDeleteAnnouncement() {
            service.deleteAnnouncement("ann-123");

            verify(announcementRepository).deleteById("ann-123");
        }
    }

    @Nested
    @DisplayName("Auditoria e Métricas de Uso")
    class AuditAndStatsTests {

        @Test
        @DisplayName("Deve registrar ação no log de auditoria com autor e detalhes")
        void shouldLogActionWithAuditDetails() {
            when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(i -> i.getArgument(0));

            AuditLog saved = service.logAction("PROMOTE_USER", "admin@totvs.com", "Usuário promovido a ADMIN");

            assertNotNull(saved.getId());
            assertNotNull(saved.getCreatedAt());
            assertEquals("PROMOTE_USER", saved.getAction());
            assertEquals("admin@totvs.com", saved.getPerformedBy());
            verify(auditLogRepository).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Deve calcular estatísticas gerais do sistema com contagens de tabelas")
        void shouldCalculateSystemStats() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(25L);

            Map<String, Object> stats = service.getSystemStats();

            assertNotNull(stats);
            assertEquals(25L, stats.get("totalUsers"));
            assertEquals(25L, stats.get("totalShowcaseSessions"));
            assertEquals(25L, stats.get("totalFeedbacks"));
        }

        @Test
        @DisplayName("Deve retornar fallback seguro de zero quando contagem de tabela falhar")
        void shouldHandleExceptionInStatsGracefully() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenThrow(new RuntimeException("Tabela temporariamente indisponível"));

            Map<String, Object> stats = service.getSystemStats();

            assertEquals(0L, stats.get("totalUsers"));
        }
    }
}
