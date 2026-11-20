package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.SquadPersonConfig;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.SquadPersonConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fusão segura de linhas do roster ({@code squad_members}) que são a MESMA pessoa com chaves diferentes, e a troca de chave
 * levando junto {@code squad_person_configs}. Nunca apaga dado útil: a linha que fica herda o que a outra tinha e ela não.
 *
 * <p>Qual linha fica: a que carrega mais identidade (vínculo com a conta {@code claimed_by_uid} &gt; e-mail &gt; cargo &gt;
 * nome de usuário em vez de chave interna JIRAUSER). Horas: o que foi calibrado à mão ({@code MANUAL_OVERRIDE}) vence o
 * que o sistema calculou; se só a linha descartada foi calibrada, as horas dela passam para a que fica.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RosterMergeService {

    static final String MANUAL = "MANUAL_OVERRIDE";

    private final SquadMemberRepository memberRepository;
    private final SquadPersonConfigRepository personConfigRepository;

    /** Pontuação de identidade da linha (maior = é a que deve ficar). */
    static int identityScore(SquadMember m) {
        int s = 0;
        if (notBlank(m.getClaimedByUid())) s += 8;
        if (notBlank(m.getEmail())) s += 4;
        if (notBlank(m.getRole())) s += 2;
        if (!PersonIdentity.isJiraServerKey(m.getJiraAccountId())) s += 1;
        return s;
    }

    /** Escolhe a linha que fica entre duas; empate: a de menor db_id (determinístico). */
    static SquadMember pickKeeper(SquadMember a, SquadMember b) {
        int sa = identityScore(a), sb = identityScore(b);
        if (sa != sb) return sa > sb ? a : b;
        return String.valueOf(a.getDbId()).compareTo(String.valueOf(b.getDbId())) <= 0 ? a : b;
    }

    /** Parte pura: copia para {@code keep} o que ela não tem e {@code drop} tem. Não mexe em chave nem em vínculo existente. */
    static void absorb(SquadMember keep, SquadMember drop) {
        if (!notBlank(keep.getDisplayName())) keep.setDisplayName(drop.getDisplayName());
        if (!notBlank(keep.getEmail())) keep.setEmail(PersonIdentity.normEmail(drop.getEmail()));
        if (!notBlank(keep.getRole())) keep.setRole(drop.getRole());
        if (!notBlank(keep.getClaimedByUid())) keep.setClaimedByUid(drop.getClaimedByUid());
        boolean keepManual = MANUAL.equalsIgnoreCase(keep.getOverrideType());
        boolean dropManual = MANUAL.equalsIgnoreCase(drop.getOverrideType());
        if (!keepManual && dropManual) {
            // Calibrado à mão vence o calculado pelo sistema: a linha mantida estava no padrão.
            keep.setCapacityHoursPerDay(drop.getCapacityHoursPerDay());
            keep.setOverrideType(drop.getOverrideType());
            keep.setCalibrationNotes(drop.getCalibrationNotes());
        } else {
            if (keep.getCapacityHoursPerDay() == null) keep.setCapacityHoursPerDay(drop.getCapacityHoursPerDay());
            if (!notBlank(keep.getOverrideType())) keep.setOverrideType(drop.getOverrideType());
            if (!notBlank(keep.getCalibrationNotes())) keep.setCalibrationNotes(drop.getCalibrationNotes());
        }
        if (keep.getSystemCalculatedCapacityHoursPerDay() == null) {
            keep.setSystemCalculatedCapacityHoursPerDay(drop.getSystemCalculatedCapacityHoursPerDay());
        }
        keep.setUpdatedAt(Instant.now().toString());
    }

    /** Funde {@code drop} em {@code keep} (mesma squad). Devolve a linha que ficou. */
    @Transactional
    public SquadMember merge(SquadMember keep, SquadMember drop) {
        if (keep == null || drop == null || keep.getDbId().equals(drop.getDbId())) return keep;
        String squadId = keep.getSquadId();
        SquadMember before = copyOf(drop);
        // O e-mail é único por squad (V85): a linha descartada sai antes de a mantida absorver o e-mail dela.
        memberRepository.delete(drop);
        memberRepository.flush();
        absorb(keep, before);
        SquadMember saved = memberRepository.save(keep);
        memberRepository.flush();
        movePersonConfigs(squadId, before.getJiraAccountId(), keep.getJiraAccountId());
        log.info("Roster {}: {} ({}) fundida em {} ({})", squadId, before.getDisplayName(), before.getJiraAccountId(),
                keep.getDisplayName(), keep.getJiraAccountId());
        return saved;
    }

    /** Procura pares da mesma pessoa no roster da squad e funde cada um. Idempotente. Devolve quantas linhas saíram. */
    @Transactional
    public int consolidate(String squadId) {
        int merged = 0;
        boolean again = true;
        while (again) {
            again = false;
            List<SquadMember> roster = new ArrayList<>(memberRepository.findBySquadIdOrderByDisplayNameAsc(squadId));
            outer:
            for (SquadMember a : roster) {
                for (SquadMember b : roster) {
                    if (a == b || a.getDbId().equals(b.getDbId())) continue;
                    if (!PersonIdentity.sameRosterPerson(a, b, roster) || !PersonIdentity.sameRosterPerson(b, a, roster)) continue;
                    SquadMember keep = pickKeeper(a, b);
                    merge(keep, keep == a ? b : a);
                    merged++;
                    again = true;
                    break outer;
                }
            }
        }
        return merged;
    }

    /** Leva as configurações por pessoa (papel/dias/horas) da chave antiga para a nova, sem perder nem sobrescrever. */
    @Transactional
    public void movePersonConfigs(String squadId, String oldId, String newId) {
        if (personConfigRepository == null || oldId == null || newId == null || oldId.equals(newId)) return;
        for (SquadPersonConfig old : personConfigRepository.findBySquadIdAndJiraAccountId(squadId, oldId)) {
            String newDbId = squadId + "_" + old.getSprintId() + "_" + newId;
            SquadPersonConfig target = personConfigRepository.findById(newDbId).orElse(null);
            if (target == null) {
                personConfigRepository.save(SquadPersonConfig.builder().dbId(newDbId).squadId(squadId).sprintId(old.getSprintId())
                        .jiraAccountId(newId).papel(old.getPapel()).diasCodificacaoTeste(old.getDiasCodificacaoTeste())
                        .diasRegressivo(old.getDiasRegressivo()).horasProdutivas(old.getHorasProdutivas())
                        .updatedAt(Instant.now().toString()).build());
            } else {
                // Já existe configuração da chave que fica: ela vale; só preenche o que estava vazio.
                if (target.getPapel() == null) target.setPapel(old.getPapel());
                if (target.getDiasCodificacaoTeste() == null) target.setDiasCodificacaoTeste(old.getDiasCodificacaoTeste());
                if (target.getDiasRegressivo() == null) target.setDiasRegressivo(old.getDiasRegressivo());
                if (target.getHorasProdutivas() == null) target.setHorasProdutivas(old.getHorasProdutivas());
                personConfigRepository.save(target);
            }
            personConfigRepository.delete(old);
        }
        personConfigRepository.flush();
    }

    private static SquadMember copyOf(SquadMember m) {
        return SquadMember.builder().dbId(m.getDbId()).squadId(m.getSquadId()).jiraAccountId(m.getJiraAccountId())
                .displayName(m.getDisplayName()).email(m.getEmail()).role(m.getRole())
                .capacityHoursPerDay(m.getCapacityHoursPerDay())
                .systemCalculatedCapacityHoursPerDay(m.getSystemCalculatedCapacityHoursPerDay())
                .calibrationNotes(m.getCalibrationNotes()).overrideType(m.getOverrideType())
                .claimedByUid(m.getClaimedByUid()).updatedAt(m.getUpdatedAt()).build();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
