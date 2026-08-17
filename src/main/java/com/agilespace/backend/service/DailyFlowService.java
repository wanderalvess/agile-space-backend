package com.agilespace.backend.service;

import com.agilespace.backend.domain.DailyReport;
import com.agilespace.backend.domain.UserWorklog;
import com.agilespace.backend.repository.DailyReportRepository;
import com.agilespace.backend.repository.UserWorklogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyFlowService {

    private final UserWorklogRepository worklogRepository;
    private final DailyReportRepository reportRepository;

    // --- User Worklogs Logic ---
    @Transactional(readOnly = true)
    public List<UserWorklog> listWorklogs(String userId, String date) {
        return worklogRepository.findByUserIdAndDate(userId, date);
    }

    @Transactional(readOnly = true)
    public List<UserWorklog> listWeeklyWorklogs(String userId, List<String> dates) {
        return worklogRepository.findByUserIdAndDateIn(userId, dates);
    }

    @Transactional
    public UserWorklog saveOrUpdateWorklog(UserWorklog log) {
        if (log.getId() == null || log.getId().trim().isEmpty()) {
            log.setId(log.getUserId() + "_" + System.currentTimeMillis());
        }
        return worklogRepository.save(log);
    }

    @Transactional
    public void deleteWorklog(String id) {
        if (!worklogRepository.existsById(id)) {
            throw new IllegalArgumentException("Worklog not found with id: " + id);
        }
        worklogRepository.deleteById(id);
    }

    // --- Daily Reports Logic ---
    @Transactional(readOnly = true)
    public List<DailyReport> listDailyReports(String userId) {
        return reportRepository.findByUserId(userId);
    }

    @Transactional
    public DailyReport saveOrUpdateDailyReport(DailyReport report) {
        // ID estruturado: {userId}_{date} para garantir um único report por dia
        String calculatedId = report.getUserId() + "_" + report.getDate();
        report.setId(calculatedId);
        return reportRepository.save(report);
    }
}
