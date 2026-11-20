package com.agilespace.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Relatório SOMENTE LEITURA de inconsistências do vínculo Pessoa x Squad x Papel (docs/membership-report.md).
 * Contagens por categoria + amostra limitada (id, e-mail, squad e um rótulo curto). Nada daqui é gravado.
 *
 * @param generatedAt momento da geração
 * @param sampleLimit máximo de linhas de amostra por categoria
 * @param totals      contagens gerais (usuários, linhas pmr por origem, roster...), para dimensionar as categorias
 * @param categories  uma entrada por tipo de inconsistência
 */
public record MembershipReportDto(Instant generatedAt, int sampleLimit, Map<String, Long> totals, List<Category> categories) {

    /**
     * @param code        identificador estável (SCREAMING_SNAKE) que o frontend/planilha pode usar como chave
     * @param title       nome curto em PT-BR
     * @param description o que a categoria significa e por que importa
     * @param count       total de ocorrências (não limitado pela amostra)
     * @param breakdown   contagem por rótulo ({@code detail}); vazio quando a categoria não tem subtipos
     * @param sample      até {@code sampleLimit} linhas
     * @param truncated   true quando {@code count} é maior que a amostra
     */
    public record Category(String code, String title, String description, long count, Map<String, Long> breakdown,
                           List<Row> sample, boolean truncated) { }

    /**
     * Uma ocorrência. Só o necessário para localizar o caso: nada de nome, foto, token ou conta do Jira.
     *
     * @param id     id do registro (usuário, linha pmr, linha do roster ou chave do projeto, conforme a categoria)
     * @param email  e-mail associado, quando houver
     * @param squad  chave da squad (ou lista de chaves) envolvida
     * @param detail rótulo curto e categórico (ex.: "sentinela", "2 linhas"); nunca texto livre da pessoa
     */
    public record Row(String id, String email, String squad, String detail) { }
}
