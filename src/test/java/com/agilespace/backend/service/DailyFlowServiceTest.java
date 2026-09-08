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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
                    .duration(120)
                    .build();

            when(worklogRepository.save(any(UserWorklog.class))).thenAnswer(i -> i.getArgument(0));

            UserWorklog saved = service.saveOrUpdateWorklog(log);

            assertNotNull(saved.getId());
            assertEquals("Desenvolvimento de testes automatizados", saved.getTitle());
            assertEquals(120, saved.getDuration());
            verify(worklogRepository).save(log);
        }

        @Test
        @DisplayName("Deve excluir worklog existente com sucesso")
        void shouldDeleteWorklogWhenExists() {
            when(worklogRepository.existsById("w1")).thenReturn(true);

            service.deleteWorklog("w1");

            verify(worklogRepository).deleteById("w1");
        }

        @Test
        @DisplayName("Deve lançar exceção ao tentar excluir worklog que não existe")
        void shouldThrowWhenDeletingNonExistentWorklog() {
            when(worklogRepository.existsById("inexistente")).thenReturn(false);

            assertThrows(IllegalArgumentException.class, () -> service.deleteWorklog("inexistente"));
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
    }
}
