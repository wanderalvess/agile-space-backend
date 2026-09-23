package com.agilespace.backend.service;

import com.agilespace.backend.domain.DailyReport;
import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.repository.DailyReportRepository;
import com.agilespace.backend.repository.UserWorklogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DailyFlowService - Registro Diário (Daily Report) e Apontamentos de Trabalho (Worklogs)")
class DailyFlowServiceTest {

    @Mock
    private UserWorklogRepository worklogRepository;

    @Mock
    private DailyReportRepository reportRepository;

    @InjectMocks
    private DailyFlowService service;

    @Nested
    @DisplayName("Gestão de Apontamentos (Worklogs)")
    class WorklogTests {

        @Test
        @DisplayName("Deve listar worklogs do usuário filtrados por data específica")
        void shouldListWorklogsByUserAndDate() {
            when(worklogRepository.findByUserIdAndDate("user-123", "2026-09-01"))
                    .thenReturn(Collections.singletonList(new UserWorklog()));

            List<UserWorklog> list = service.listWorklogs("user-123", "2026-09-01");

            assertEquals(1, list.size());
            verify(worklogRepository).findByUserIdAndDate("user-123", "2026-09-01");
        }

        @Test
        @DisplayName("Deve listar worklogs semanais do usuário em um intervalo de datas")
        void shouldListWeeklyWorklogs() {
            List<String> weekDates = Arrays.asList("2026-09-01", "2026-09-02");
            when(worklogRepository.findByUserIdAndDateIn("user-123", weekDates))
                    .thenReturn(Collections.singletonList(new UserWorklog()));

            List<UserWorklog> list = service.listWeeklyWorklogs("user-123", weekDates);

            assertEquals(1, list.size());
            verify(worklogRepository).findByUserIdAndDateIn("user-123", weekDates);
        }

        @Test
        @DisplayName("Deve salvar apontamento gerando UUID automaticamente")
        void shouldSaveWorklogWithGeneratedId() {
            UserWorklog log = UserWorklog.builder()
                    .userId("user-123")
                    .title("Desenvolvimento de testes automatizados")
                    .durationMinutes(120)
                    .build();

            when(worklogRepository.save(any(UserWorklog.class))).thenAnswer(i -> i.getArgument(0));

            UserWorklog saved = service.saveOrUpdateWorklog(log);

            assertNotNull(saved.getId());
            assertEquals("Desenvolvimento de testes automatizados", saved.getTitle());
            assertEquals(120, saved.getDurationMinutes());
            verify(worklogRepository).save(log);
        }

        @Test
        @DisplayName("Deve excluir worklog existente do próprio dono com sucesso")
        void shouldDeleteWorklogWhenOwnedByCaller() {
            UserWorklog log = UserWorklog.builder().id("w1").userId("user-123").build();
            when(worklogRepository.findById("w1")).thenReturn(Optional.of(log));

            service.deleteWorklog("w1", "user-123", false);

            verify(worklogRepository).deleteById("w1");
        }

        @Test
        @DisplayName("ADMIN pode excluir worklog de outro usuário")
        void shouldAllowAdminToDeleteAnyWorklog() {
            UserWorklog log = UserWorklog.builder().id("w1").userId("user-123").build();
            when(worklogRepository.findById("w1")).thenReturn(Optional.of(log));

            service.deleteWorklog("w1", "admin-boss", true);

            verify(worklogRepository).deleteById("w1");
        }

        @Test
        @DisplayName("Deve rejeitar com 403 exclusão de worklog de outro usuário")
        void shouldRejectDeleteWorklogFromAnotherUser() {
            UserWorklog log = UserWorklog.builder().id("w1").userId("user-123").build();
            when(worklogRepository.findById("w1")).thenReturn(Optional.of(log));

            assertThrows(ResponseStatusException.class, () -> service.deleteWorklog("w1", "intruso", false));
            verify(worklogRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("Deve lançar exceção ao tentar excluir worklog que não existe")
        void shouldThrowWhenDeletingNonExistentWorklog() {
            when(worklogRepository.findById("inexistente")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.deleteWorklog("inexistente", "user-123", false));
        }
    }

    @Nested
    @DisplayName("Gestão do Relatório Diário (Daily Report)")
    class DailyReportTests {

        @Test
        @DisplayName("Deve listar relatórios da daily de um determinado usuário")
        void shouldListDailyReports() {
            when(reportRepository.findByUserId("user-123"))
                    .thenReturn(Collections.singletonList(new DailyReport()));

            List<DailyReport> list = service.listDailyReports("user-123");

            assertEquals(1, list.size());
            verify(reportRepository).findByUserId("user-123");
        }

        @Test
        @DisplayName("Deve salvar daily report gerando chave composta userId_data")
        void shouldSaveDailyReportWithCompositeKey() {
            DailyReport report = DailyReport.builder()
                    .userId("user-123")
                    .date("2026-09-01")
                    .yesterday("Finalizado refatoração dos controladores")
                    .today("Iniciando testes unitários do frontend")
                    .build();

            when(reportRepository.save(any(DailyReport.class))).thenAnswer(i -> i.getArgument(0));

            DailyReport saved = service.saveOrUpdateDailyReport(report);

            assertEquals("user-123_2026-09-01", saved.getId());
            assertEquals("user-123", saved.getUserId());
            assertEquals("Finalizado refatoração dos controladores", saved.getYesterday());
            verify(reportRepository).save(report);
        }

        @Test
        @DisplayName("Deve excluir daily report existente do próprio dono com sucesso")
        void shouldDeleteDailyReportWhenOwnedByCaller() {
            DailyReport report = DailyReport.builder().id("user-123_2026-09-01").userId("user-123").build();
            when(reportRepository.findById("user-123_2026-09-01")).thenReturn(Optional.of(report));

            service.deleteDailyReport("user-123_2026-09-01", "user-123", false);

            verify(reportRepository).deleteById("user-123_2026-09-01");
        }

        @Test
        @DisplayName("Deve rejeitar com 403 exclusão de daily report de outro usuário")
        void shouldRejectDeleteDailyReportFromAnotherUser() {
            DailyReport report = DailyReport.builder().id("user-123_2026-09-01").userId("user-123").build();
            when(reportRepository.findById("user-123_2026-09-01")).thenReturn(Optional.of(report));

            assertThrows(ResponseStatusException.class, () -> service.deleteDailyReport("user-123_2026-09-01", "intruso", false));
            verify(reportRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("Deve lançar exceção ao tentar excluir daily report que não existe")
        void shouldThrowWhenDeletingNonExistentDailyReport() {
            when(reportRepository.findById("inexistente")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.deleteDailyReport("inexistente", "user-123", false));
        }
    }
}
