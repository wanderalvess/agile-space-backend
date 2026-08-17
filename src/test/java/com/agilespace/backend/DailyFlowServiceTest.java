package com.agilespace.backend;

import com.agilespace.backend.domain.DailyReport;
import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.repository.DailyReportRepository;
import com.agilespace.backend.repository.UserWorklogRepository;
import com.agilespace.backend.service.DailyFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class DailyFlowServiceTest {

    @Mock
    private UserWorklogRepository worklogRepository;

    @Mock
    private DailyReportRepository reportRepository;

    @InjectMocks
    private DailyFlowService service;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testListWorklogs() {
        when(worklogRepository.findByUserIdAndDate("u1", "2026-08-16")).thenReturn(Arrays.asList(new UserWorklog()));
        List<UserWorklog> list = service.listWorklogs("u1", "2026-08-16");
        assertEquals(1, list.size());
    }

    @Test
    public void testListWeeklyWorklogs() {
        when(worklogRepository.findByUserIdAndDateIn("u1", Arrays.asList("d1", "d2"))).thenReturn(Arrays.asList(new UserWorklog()));
        List<UserWorklog> list = service.listWeeklyWorklogs("u1", Arrays.asList("d1", "d2"));
        assertEquals(1, list.size());
    }

    @Test
    public void testSaveWorklogGeneratesId() {
        UserWorklog log = UserWorklog.builder()
                .userId("user-123")
                .title("Escrever testes de backend")
                .build();

        when(worklogRepository.save(any(UserWorklog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserWorklog saved = service.saveOrUpdateWorklog(log);

        assertNotNull(saved.getId());
        assertEquals("Escrever testes de backend", saved.getTitle());
        verify(worklogRepository, times(1)).save(log);
    }

    @Test
    public void testDeleteWorklogSuccess() {
        when(worklogRepository.existsById("w1")).thenReturn(true);
        service.deleteWorklog("w1");
        verify(worklogRepository).deleteById("w1");
    }

    @Test
    public void testDeleteWorklogNotFound() {
        when(worklogRepository.existsById("w1")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.deleteWorklog("w1"));
    }

    @Test
    public void testListDailyReports() {
        when(reportRepository.findByUserId("u1")).thenReturn(Arrays.asList(new DailyReport()));
        List<DailyReport> list = service.listDailyReports("u1");
        assertEquals(1, list.size());
    }

    @Test
    public void testSaveReportGeneratesId() {
        DailyReport report = DailyReport.builder()
                .userId("user-123")
                .date("2026-08-14")
                .yesterday("Criado entidades")
                .today("Criado controllers")
                .build();

        when(reportRepository.save(any(DailyReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DailyReport saved = service.saveOrUpdateDailyReport(report);

        assertNotNull(saved.getId());
        assertEquals("user-123_2026-08-14", saved.getId());
        assertEquals("user-123", saved.getUserId());
        verify(reportRepository, times(1)).save(report);
    }
}
