package com.agilespace.backend.service;

import com.agilespace.backend.domain.DailyCheckin;
import com.agilespace.backend.repository.DailyCheckinRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyCheckinService {

    private final DailyCheckinRepository checkinRepository;

    @Transactional(readOnly = true)
    public List<DailyCheckin> listBySquadAndDate(String squadId, String date) {
        return checkinRepository.findBySquadIdAndDate(squadId, date);
    }

    @Transactional(readOnly = true)
    public List<DailyCheckin> listBySquadAndDateRange(String squadId, String startDate, String endDate) {
        return checkinRepository.findBySquadIdAndDateBetween(squadId, startDate, endDate);
    }

    @Transactional
    public DailyCheckin saveOrUpdate(DailyCheckin checkin) {
        // ID estruturado: {userId}_{date}, mesmo padrão do DailyReport, garante um único checkin por dia/pessoa.
        checkin.setId(checkin.getUserId() + "_" + checkin.getDate());
        return checkinRepository.save(checkin);
    }

    @Transactional
    public List<DailyCheckin> saveOrUpdateBatch(List<DailyCheckin> checkins) {
        checkins.forEach(c -> c.setId(c.getUserId() + "_" + c.getDate()));
        return checkinRepository.saveAll(checkins);
    }

    @Transactional
    public void delete(String id) {
        if (!checkinRepository.existsById(id)) {
            throw new IllegalArgumentException("Daily checkin not found with id: " + id);
        }
        checkinRepository.deleteById(id);
    }
}
