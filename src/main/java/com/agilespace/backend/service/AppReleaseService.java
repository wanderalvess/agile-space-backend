package com.agilespace.backend.service;

import com.agilespace.backend.domain.AppRelease;
import com.agilespace.backend.dto.AppReleaseDTO;
import com.agilespace.backend.repository.AppReleaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AppReleaseService {

    @Autowired
    private AppReleaseRepository repository;

    @Transactional
    public List<AppRelease> getPublishedReleases() {
        List<AppRelease> releases = repository.findByIsPublishedTrueOrderByCreatedAtDesc();
        if (releases.isEmpty()) {
            seedInitialReleasesIfEmpty();
            releases = repository.findByIsPublishedTrueOrderByCreatedAtDesc();
        }
        return releases;
    }

    @Transactional(readOnly = true)
    public Optional<AppRelease> getLatestRelease() {
        Optional<AppRelease> release = repository.findFirstByIsPublishedTrueOrderByCreatedAtDesc();
        if (release.isEmpty() && repository.count() == 0) {
            seedInitialReleasesIfEmpty();
            release = repository.findFirstByIsPublishedTrueOrderByCreatedAtDesc();
        }
        return release;
    }

    @Transactional
    public List<AppRelease> getAllReleasesAdmin() {
        List<AppRelease> releases = repository.findAllByOrderByCreatedAtDesc();
        if (releases.isEmpty()) {
            seedInitialReleasesIfEmpty();
            releases = repository.findAllByOrderByCreatedAtDesc();
        }
        return releases;
    }

    @Transactional(readOnly = true)
    public Optional<AppRelease> getReleaseById(String id) {
        return repository.findById(id);
    }

    @Transactional
    public void seedInitialReleasesIfEmpty() {
        if (repository.count() > 0) {
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource("changelog-seed.json");
            if (resource.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                try (InputStream inputStream = resource.getInputStream()) {
                    List<AppReleaseDTO> seedReleases = mapper.readValue(inputStream, new TypeReference<List<AppReleaseDTO>>() {});
                    importLegacyReleases(seedReleases);
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Erro ao carregar changelog-seed.json: " + e.getMessage());
        }

        List<AppReleaseDTO> defaultReleases = Arrays.asList(
            AppReleaseDTO.builder()
                .tag("v4.0.0")
                .type("major")
                .title("Arquitetura Next.js 16 & Java 21 Spring Boot — Espaço Ágil v4")
                .description("Evolução estrutural completa da suíte com backend Spring Boot, persistência JPA, inteligência artificial integrada e nova central de versões.")
                .displayDate("25 de Agosto, 2026")
                .iconName("Rocket")
                .iconClass("h-5 w-5 text-primary")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Migração do frontend para Next.js 16 (App Router) com suporte a React 19.",
                    "Backend reestruturado em Java 21 Spring Boot com Spring Security e JPA/Hibernate.",
                    "Nova central de Changelog e Gerenciamento de Versões com persistência no banco.",
                    "Integração com IA Generativa para assistência em cerimoniais ágeis."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.120.0")
                .type("minor")
                .title("Assistente de IA Generativa & Stitch MCP Integration")
                .description("Inclusão de copiloto IA para auxílio na criação de estórias, critérios de aceite e resumos de retrospectiva.")
                .displayDate("22 de Agosto, 2026")
                .iconName("Sparkles")
                .iconClass("h-5 w-5 text-emerald-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Geração automática de rascunhos de estórias de usuário.",
                    "Sumarização inteligente de pontos de melhoria pós-retrospectiva.",
                    "Suporte a chamadas de ferramentas MCP e contexto síncrono."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.117.1")
                .type("patch")
                .title("Otimizações de Layout, RoomHeader & Central de Changelog")
                .description("Melhorias no consumo da API de releases, busca por termos e alinhamento visual.")
                .displayDate("17 de Agosto, 2026")
                .iconName("Zap")
                .iconClass("h-5 w-5 text-indigo-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Filtros refinados por tipo de release (Major, Minor, Patch e Rascunhos).",
                    "Ajuste na renderização de ícones dinâmicos do Lucide React.",
                    "Tratamento resiliente para carregamento de versões em offline e fallback."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.115.0")
                .type("minor")
                .title("Cofre de Segredos (Vault) & Trilha de Auditoria")
                .description("Módulo para gerenciamento seguro de credenciais, tokens de API Jira e chaves de integração.")
                .displayDate("10 de Agosto, 2026")
                .iconName("ShieldCheck")
                .iconClass("h-5 w-5 text-emerald-600")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Criptografia de segredos e chaves de integração de serviços externos.",
                    "Log de auditoria para ações administrativas e acessos a dados sensíveis."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.110.0")
                .type("minor")
                .title("Módulo Squad Health Check & Radar de Engenharia")
                .description("Acompanhamento contínuo dos pilares de saúde da squad: velocidade, qualidade, alinhamento e clima.")
                .displayDate("01 de Agosto, 2026")
                .iconName("Sparkles")
                .iconClass("h-5 w-5 text-emerald-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Matriz de avaliação de saúde com métricas visuais em tempo real.",
                    "Histórico comparativo de evolução entre sprints."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.100.0")
                .type("minor")
                .title("Knowledge Base Hub & Documentação de Squad")
                .description("Central unificada para armazenamento de guias de arquitetura, decisões técnicas (ADRs) e onboarding.")
                .displayDate("20 de Julho, 2026")
                .iconName("Database")
                .iconClass("h-5 w-5 text-cyan-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Editor de documentação rica com suporte a Markdown e Mermaid.",
                    "Busca semântica acelerada em documentos da squad."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.80.0")
                .type("minor")
                .title("Integração Jira Cloud de Alta Performance")
                .description("Conexão direta com Jira REST API para sincronização bidirecional de estórias, epics e sprints.")
                .displayDate("12 de Julho, 2026")
                .iconName("Globe")
                .iconClass("h-5 w-5 text-teal-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Importação automática de backlog para Planning Poker.",
                    "Atualização de pontuação de estimativa diretamente no Jira."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.50.0")
                .type("minor")
                .title("Action Plan Manager — Planos de Ação Pós-Retro")
                .description("Transformação de itens de melhoria em tarefas rastreáveis com responsáveis e prazos definidos.")
                .displayDate("02 de Julho, 2026")
                .iconName("ListChecks")
                .iconClass("h-5 w-5 text-purple-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Criação e acompanhamento de tarefas derivadas de retrospectivas.",
                    "Exportação de planos de ação em PDF e Markdown."
                ))
                .build(),
            AppReleaseDTO.builder()
                .tag("v3.0.0")
                .type("major")
                .title("Lançamento da Plataforma Espaço Ágil 3.0")
                .description("Primeira versão da suíte integrada para facilitação de cerimoniais ágeis e produtividade síncrona.")
                .displayDate("24 de Junho, 2026")
                .iconName("Layers")
                .iconClass("h-5 w-5 text-blue-500")
                .isPublished(true)
                .createdBy("Sistema")
                .changes(Arrays.asList(
                    "Planning Poker interativo com estimativa por Fibonacci.",
                    "Daily Flow para acompanhamento de impedimentos diários.",
                    "Retrospectivas síncronas com votação em tempo real."
                ))
                .build()
        );

        for (AppReleaseDTO dto : defaultReleases) {
            try {
                createRelease(dto);
            } catch (Exception ignored) {
            }
        }
    }

    @Transactional
    public AppRelease createRelease(AppReleaseDTO dto) {
        if (dto.getTag() == null || dto.getTag().trim().isEmpty()) {
            throw new IllegalArgumentException("A tag da versão é obrigatória.");
        }

        if (repository.existsByTag(dto.getTag().trim())) {
            throw new IllegalArgumentException("Já existe uma versão cadastrada com a tag " + dto.getTag());
        }

        AppRelease release = AppRelease.builder()
                .id(UUID.randomUUID().toString())
                .tag(dto.getTag().trim())
                .title(dto.getTitle() != null ? dto.getTitle().trim() : "")
                .description(dto.getDescription() != null ? dto.getDescription().trim() : "")
                .changes(dto.getChanges() != null ? new ArrayList<>(dto.getChanges()) : new ArrayList<>())
                .type(dto.getType() != null ? dto.getType().toLowerCase().trim() : "patch")
                .iconName(dto.getIconName() != null ? dto.getIconName().trim() : "Zap")
                .iconClass(dto.getIconClass() != null ? dto.getIconClass().trim() : "h-5 w-5 text-indigo-500")
                .displayDate(dto.getDisplayDate() != null ? dto.getDisplayDate().trim() : "")
                .isPublished(dto.getIsPublished() != null ? dto.getIsPublished() : true)
                .createdBy(dto.getCreatedBy() != null ? dto.getCreatedBy().trim() : "Admin")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return repository.save(release);
    }

    @Transactional
    public AppRelease updateRelease(String id, AppReleaseDTO dto) {
        AppRelease existing = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Versão não encontrada com id: " + id));

        if (dto.getTag() != null && !dto.getTag().trim().equals(existing.getTag())) {
            if (repository.existsByTag(dto.getTag().trim())) {
                throw new IllegalArgumentException("Já existe uma versão cadastrada com a tag " + dto.getTag());
            }
            existing.setTag(dto.getTag().trim());
        }

        if (dto.getTitle() != null) existing.setTitle(dto.getTitle().trim());
        if (dto.getDescription() != null) existing.setDescription(dto.getDescription().trim());
        if (dto.getChanges() != null) existing.setChanges(new ArrayList<>(dto.getChanges()));
        if (dto.getType() != null) existing.setType(dto.getType().toLowerCase().trim());
        if (dto.getIconName() != null) existing.setIconName(dto.getIconName().trim());
        if (dto.getIconClass() != null) existing.setIconClass(dto.getIconClass().trim());
        if (dto.getDisplayDate() != null) existing.setDisplayDate(dto.getDisplayDate().trim());
        if (dto.getIsPublished() != null) existing.setIsPublished(dto.getIsPublished());

        existing.setUpdatedAt(LocalDateTime.now());
        return repository.save(existing);
    }

    @Transactional
    public void deleteRelease(String id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("Versão não encontrada com id: " + id);
        }
        repository.deleteById(id);
    }

    @Transactional
    public Map<String, Object> importLegacyReleases(List<AppReleaseDTO> dtos) {
        int imported = 0;
        int skipped = 0;

        // Inverter ou ordenar para que as mais antigas recebam createdAt anterior caso queira manter a ordem cronológica
        // Dtos geralmente vem do mais novo para o mais antigo.
        // Vamos iterar e definir createdAt decrementado para manter ordem exata
        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = dtos.size() - 1; i >= 0; i--) {
            AppReleaseDTO dto = dtos.get(i);
            if (dto.getTag() == null || dto.getTag().trim().isEmpty()) {
                skipped++;
                continue;
            }

            String tag = dto.getTag().trim();
            if (repository.existsByTag(tag)) {
                skipped++;
                continue;
            }

            LocalDateTime releaseTime = baseTime.minusSeconds((long) (dtos.size() - 1 - i) * 60);

            AppRelease release = AppRelease.builder()
                    .id(UUID.randomUUID().toString())
                    .tag(tag)
                    .title(dto.getTitle() != null ? dto.getTitle() : "")
                    .description(dto.getDescription() != null ? dto.getDescription() : "")
                    .changes(dto.getChanges() != null ? new ArrayList<>(dto.getChanges()) : new ArrayList<>())
                    .type(dto.getType() != null ? dto.getType() : "patch")
                    .iconName(dto.getIconName() != null ? dto.getIconName() : "Zap")
                    .iconClass(dto.getIconClass() != null ? dto.getIconClass() : "h-5 w-5 text-indigo-500")
                    .displayDate(dto.getDisplayDate() != null ? dto.getDisplayDate() : "")
                    .isPublished(dto.getIsPublished() != null ? dto.getIsPublished() : true)
                    .createdBy("Legacy Import")
                    .createdAt(releaseTime)
                    .updatedAt(releaseTime)
                    .build();

            repository.save(release);
            imported++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("total", dtos.size());
        return result;
    }
}
