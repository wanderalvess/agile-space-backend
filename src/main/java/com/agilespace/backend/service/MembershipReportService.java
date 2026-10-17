package com.agilespace.backend.service;

import com.agilespace.backend.dto.MembershipReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório de inconsistências do vínculo Pessoa x Squad x Papel, SOMENTE LEITURA (transação readOnly: o Postgres
 * recusa qualquer escrita aqui). Executa as consultas da seção 3 de docs/vinculo-auditoria-e-plano.md e devolve contagem
 * por categoria mais uma amostra de até {@value #SAMPLE_LIMIT} linhas (id, e-mail, squad, rótulo). Contrato em
 * docs/membership-report.md.
 *
 * <p>Cada categoria é uma consulta "base" com as colunas (id, email, squad, detail). A contagem, a quebra por rótulo e a
 * amostra são derivadas dela por envelopes, então a categoria nunca diverge entre os três números.
 */
@Service
@RequiredArgsConstructor
public class MembershipReportService {

    static final int SAMPLE_LIMIT = 20;
    private static final int BREAKDOWN_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final AuthzDiffService authzDiff;

    /**
     * CTE que espelha o resolvedor (UserProjectResolverService.resolveUserAccess): a linha pmr pertence à conta por
     * user_id OU e-mail OU conta do Jira. Uma linha que casa com duas contas aparece para as duas, como no resolvedor.
     */
    private static final String PMR_U = """
            WITH pmr_u AS (
              SELECT pmr.id AS pmr_id, UPPER(BTRIM(pmr.project_id)) AS project_up, pmr.role_name,
                     UPPER(pmr.role_key) AS role_key, pmr.is_leadership, u.id AS user_id
              FROM project_member_roles pmr
              JOIN users u
                ON u.id = pmr.user_id
                OR (pmr.email IS NOT NULL AND BTRIM(pmr.email) <> '' AND LOWER(BTRIM(pmr.email)) = LOWER(BTRIM(u.email)))
                OR (pmr.jira_account_id IS NOT NULL AND pmr.jira_account_id <> '' AND pmr.jira_account_id = u.jira_account_id)
            )
            """;

    private static final String APPROVER_KEYS = "('AGILE_MASTER','SCRUM_MASTER','PRODUCT_OWNER','PEOPLE_LEAD')";

    private record CategoryDef(String code, String title, String description, String baseSql) { }

    private static final List<CategoryDef> CATEGORIES = List.of(
            new CategoryDef("USERS_WITH_SQUAD_WITHOUT_LINK",
                    "Usuário com squad e sem vínculo nela",
                    "Conta ativa com users.squad_id (ou default_project_id) mas sem linha em project_member_roles nessa squad. "
                            + "Rótulos: sentinela (Sem Time), squad_id_nao_e_projeto (nome/grafia/squad só do roster), so_squad_members "
                            + "(só o roster dá acesso) e sem_vinculo_nenhum. Perde o acesso quando a leitura passar só para os vínculos.",
                    PMR_U + """
                            , cur AS (
                              SELECT u.id, u.email, u.jira_account_id,
                                     COALESCE(NULLIF(BTRIM(u.squad_id), ''), NULLIF(BTRIM(u.default_project_id), '')) AS squad_raw
                              FROM users u WHERE u.is_active IS TRUE
                            )
                            SELECT c.id AS id, c.email AS email, c.squad_raw AS squad,
                              CASE
                                WHEN UPPER(c.squad_raw) IN ('SEM TIME', 'SEM SQUAD') THEN 'sentinela'
                                WHEN NOT EXISTS (SELECT 1 FROM projects p WHERE UPPER(p.id) = UPPER(c.squad_raw)) THEN 'squad_id_nao_e_projeto'
                                WHEN EXISTS (SELECT 1 FROM squad_members sm WHERE UPPER(sm.squad_id) = UPPER(c.squad_raw)
                                             AND (sm.claimed_by_uid = c.id OR LOWER(sm.email) = LOWER(c.email)
                                                  OR sm.jira_account_id = c.jira_account_id)) THEN 'so_squad_members'
                                ELSE 'sem_vinculo_nenhum'
                              END AS detail
                            FROM cur c
                            WHERE c.squad_raw IS NOT NULL
                              AND NOT EXISTS (
                                SELECT 1 FROM pmr_u x
                                WHERE x.user_id = c.id
                                  AND (x.project_up = UPPER(c.squad_raw)
                                       OR (x.project_up IN ('DDWMISSI', 'MISSI') AND UPPER(c.squad_raw) IN ('DDWMISSI', 'MISSI'))))
                            """),
            new CategoryDef("ROSTER_WITHOUT_LINK",
                    "Roster (squad_members) sem vínculo",
                    "Linha de squad_members sem linha correspondente em project_member_roles (por conta, e-mail ou conta do Jira) "
                            + "e que não foi removida à mão. Hoje já dá acesso de leitura; some quando o acesso vier só dos vínculos. "
                            + "Rótulos: projeto_existe ou squad_so_roster (squad sem linha em projects).",
                    """
                            SELECT sm.db_id AS id, sm.email AS email, sm.squad_id AS squad,
                              CASE WHEN EXISTS (SELECT 1 FROM projects p WHERE UPPER(p.id) = UPPER(sm.squad_id))
                                   THEN 'projeto_existe' ELSE 'squad_so_roster' END AS detail
                            FROM squad_members sm
                            WHERE NOT EXISTS (
                                    SELECT 1 FROM project_member_roles pmr
                                    WHERE UPPER(pmr.project_id) = UPPER(sm.squad_id)
                                      AND ((sm.claimed_by_uid IS NOT NULL AND pmr.user_id = sm.claimed_by_uid)
                                           OR (sm.email IS NOT NULL AND pmr.email IS NOT NULL AND LOWER(BTRIM(pmr.email)) = LOWER(BTRIM(sm.email)))
                                           OR pmr.jira_account_id = sm.jira_account_id))
                              AND NOT EXISTS (
                                    SELECT 1 FROM squad_member_exclusions e
                                    WHERE e.squad_id = sm.squad_id
                                      AND (e.jira_account_id = sm.jira_account_id
                                           OR (e.email IS NOT NULL AND sm.email IS NOT NULL AND LOWER(e.email) = LOWER(sm.email))))
                            """),
            new CategoryDef("EXCLUDED_BUT_STILL_LINKED",
                    "Removido à mão que ainda tem vínculo",
                    "Pessoa em squad_member_exclusions (removida do time) que ainda tem linha pmr ou users.squad_id nessa squad.",
                    """
                            SELECT e.db_id AS id, e.email AS email, e.squad_id AS squad,
                              CASE WHEN ainda_pmr AND ainda_squad_id THEN 'pmr_e_squad_id'
                                   WHEN ainda_pmr THEN 'pmr' ELSE 'squad_id' END AS detail
                            FROM (
                              SELECT e.*,
                                EXISTS (SELECT 1 FROM project_member_roles pmr WHERE UPPER(pmr.project_id) = UPPER(e.squad_id)
                                        AND (pmr.jira_account_id = e.jira_account_id
                                             OR (e.email IS NOT NULL AND LOWER(pmr.email) = LOWER(e.email)))) AS ainda_pmr,
                                EXISTS (SELECT 1 FROM users u WHERE UPPER(u.squad_id) = UPPER(e.squad_id)
                                        AND (u.jira_account_id = e.jira_account_id
                                             OR (e.email IS NOT NULL AND LOWER(u.email) = LOWER(e.email)))) AS ainda_squad_id
                              FROM squad_member_exclusions e
                            ) e
                            WHERE ainda_pmr OR ainda_squad_id
                            """),
            new CategoryDef("SAME_SQUAD_MULTIPLE_ROWS",
                    "Mesma pessoa com várias linhas na mesma squad",
                    "A conta tem 2 ou mais linhas pmr na mesma squad. O resolvedor mostra uma só: a de liderança vence. "
                            + "Rótulos: lideranca_e_contribuidor, varias_liderancas, varios_contribuidores.",
                    PMR_U + """
                            SELECT p.user_id AS id, u.email AS email, p.project_up AS squad,
                              CASE WHEN bool_or(p.is_leadership) AND NOT bool_and(p.is_leadership) THEN 'lideranca_e_contribuidor'
                                   WHEN bool_and(p.is_leadership) THEN 'varias_liderancas'
                                   ELSE 'varios_contribuidores' END AS detail
                            FROM pmr_u p JOIN users u ON u.id = p.user_id
                            GROUP BY p.user_id, u.email, p.project_up
                            HAVING COUNT(DISTINCT p.pmr_id) > 1
                            """),
            new CategoryDef("USER_IN_MULTIPLE_SQUADS",
                    "Pessoa em mais de uma squad",
                    "A conta tem vínculo em 2 ou mais squads (esperado para quem atua em várias; confira o campo único users.squad_id). "
                            + "O rótulo traz a quantidade.",
                    PMR_U + """
                            SELECT p.user_id AS id, u.email AS email, LEFT(string_agg(DISTINCT p.project_up, ','), 200) AS squad,
                                   COUNT(DISTINCT p.project_up) || ' squads' AS detail
                            FROM pmr_u p JOIN users u ON u.id = p.user_id
                            GROUP BY p.user_id, u.email
                            HAVING COUNT(DISTINCT p.project_up) > 1
                            """),
            new CategoryDef("ROSTER_DUPLICATE_CLAIMS",
                    "Mesma conta reivindicando várias linhas do roster na squad",
                    "squad_members com a mesma claimed_by_uid mais de uma vez na mesma squad.",
                    """
                            SELECT sm.claimed_by_uid AS id, MIN(sm.email) AS email, sm.squad_id AS squad, COUNT(*) || ' linhas' AS detail
                            FROM squad_members sm
                            WHERE sm.claimed_by_uid IS NOT NULL
                            GROUP BY sm.claimed_by_uid, sm.squad_id
                            HAVING COUNT(*) > 1
                            """),
            new CategoryDef("SQUADS_WITHOUT_LEADERSHIP",
                    "Squad sem liderança cadastrada",
                    "Projeto sem nenhuma linha pmr com is_leadership. Hoje qualquer membro gerencia essa squad (fallback). "
                            + "Rótulos: sem_nenhuma_pessoa ou so_contribuidores.",
                    """
                            SELECT p.id AS id, NULL::text AS email, p.id AS squad,
                              CASE WHEN COUNT(pmr.id) = 0 THEN 'sem_nenhuma_pessoa' ELSE 'so_contribuidores' END AS detail
                            FROM projects p
                            LEFT JOIN project_member_roles pmr ON UPPER(pmr.project_id) = UPPER(p.id)
                            GROUP BY p.id
                            HAVING COUNT(*) FILTER (WHERE pmr.is_leadership) = 0
                            """),
            new CategoryDef("SQUADS_WITHOUT_APPROVER",
                    "Squad sem aprovador de pedidos",
                    "Nenhuma liderança AM/SM/PO/PL na squad, ou nenhuma delas com conta (user_id, e-mail ou conta do Jira). "
                            + "Pedidos de contribuidor não teriam quem aprovar (etapa 3). Rótulos: sem_aprovador ou aprovador_sem_conta.",
                    """
                            SELECT p.id AS id, NULL::text AS email, p.id AS squad,
                              CASE WHEN COUNT(*) FILTER (WHERE r.approver) = 0 THEN 'sem_aprovador' ELSE 'aprovador_sem_conta' END AS detail
                            FROM projects p
                            LEFT JOIN (
                              SELECT UPPER(pmr.project_id) AS pid,
                                     (pmr.is_leadership AND UPPER(pmr.role_key) IN %s) AS approver,
                                     (pmr.user_id IS NOT NULL OR EXISTS (
                                        SELECT 1 FROM users u
                                        WHERE (pmr.email IS NOT NULL AND LOWER(BTRIM(u.email)) = LOWER(BTRIM(pmr.email)))
                                           OR (pmr.jira_account_id IS NOT NULL AND pmr.jira_account_id <> '' AND u.jira_account_id = pmr.jira_account_id)
                                     )) AS has_account
                              FROM project_member_roles pmr
                            ) r ON r.pid = UPPER(p.id)
                            GROUP BY p.id
                            HAVING COUNT(*) FILTER (WHERE r.approver) = 0
                                OR COUNT(*) FILTER (WHERE r.approver AND r.has_account) = 0
                            """.formatted(APPROVER_KEYS)),
            new CategoryDef("PMR_USER_MISSING",
                    "Vínculo apontando para conta inexistente",
                    "project_member_roles.user_id preenchido com um id que não existe em users.",
                    """
                            SELECT pmr.id AS id, pmr.email AS email, pmr.project_id AS squad, 'conta_inexistente' AS detail
                            FROM project_member_roles pmr
                            LEFT JOIN users u ON u.id = pmr.user_id
                            WHERE pmr.user_id IS NOT NULL AND u.id IS NULL
                            """),
            new CategoryDef("ROSTER_CLAIM_USER_MISSING",
                    "Roster reivindicado por conta inexistente",
                    "squad_members.claimed_by_uid com um id que não existe em users.",
                    """
                            SELECT sm.db_id AS id, sm.email AS email, sm.squad_id AS squad, 'conta_inexistente' AS detail
                            FROM squad_members sm
                            LEFT JOIN users u ON u.id = sm.claimed_by_uid
                            WHERE sm.claimed_by_uid IS NOT NULL AND u.id IS NULL
                            """),
            new CategoryDef("CLAIM_DIVERGENT",
                    "Claim do roster diverge do vínculo (pmr)",
                    "Mesma pessoa/squad em squad_members e project_member_roles com contas diferentes ou só um dos lados reivindicado. "
                            + "Rótulos: roster_sem_claim_pmr_com, pmr_sem_claim_roster_com, contas_diferentes.",
                    """
                            SELECT DISTINCT sm.db_id AS id, sm.email AS email, sm.squad_id AS squad,
                              CASE WHEN sm.claimed_by_uid IS NULL THEN 'roster_sem_claim_pmr_com'
                                   WHEN pmr.user_id IS NULL THEN 'pmr_sem_claim_roster_com'
                                   ELSE 'contas_diferentes' END AS detail
                            FROM squad_members sm
                            JOIN project_member_roles pmr
                              ON UPPER(pmr.project_id) = UPPER(sm.squad_id)
                             AND (pmr.jira_account_id = sm.jira_account_id
                                  OR (sm.email IS NOT NULL AND pmr.email IS NOT NULL AND LOWER(BTRIM(pmr.email)) = LOWER(BTRIM(sm.email))))
                            WHERE sm.claimed_by_uid IS DISTINCT FROM pmr.user_id
                              AND (sm.claimed_by_uid IS NOT NULL OR pmr.user_id IS NOT NULL)
                            """),
            new CategoryDef("JIRA_ACCOUNT_DUPLICATED",
                    "Mesma conta do Jira em mais de um usuário",
                    "users.jira_account_id repetido: o casamento por conta do Jira pode ligar a pessoa errada.",
                    """
                            SELECT u.id AS id, u.email AS email, u.squad_id AS squad, 'jira_account_duplicada' AS detail
                            FROM users u
                            WHERE u.jira_account_id IS NOT NULL AND u.jira_account_id <> ''
                              AND u.jira_account_id IN (
                                SELECT jira_account_id FROM users
                                WHERE jira_account_id IS NOT NULL AND jira_account_id <> ''
                                GROUP BY jira_account_id HAVING COUNT(*) > 1)
                            """),
            new CategoryDef("PHANTOM_ACCOUNTS",
                    "Contas-fantasma do sync do Jira",
                    "Usuário sem senha cujo e-mail termina em @empresa.com ou cujo id é a própria conta do Jira (criado pelo sync). "
                            + "Rótulos: ativa ou inativa.",
                    """
                            SELECT u.id AS id, u.email AS email, u.squad_id AS squad,
                                   CASE WHEN u.is_active IS TRUE THEN 'ativa' ELSE 'inativa' END AS detail
                            FROM users u
                            WHERE u.password_hash IS NULL
                              AND (LOWER(u.email) LIKE '%@empresa.com' OR (u.jira_account_id IS NOT NULL AND u.id = u.jira_account_id))
                            """),
            new CategoryDef("ROSTER_EMAIL_WITHOUT_ACCOUNT",
                    "E-mail do roster sem conta",
                    "Linha de project_member_roles ou squad_members com e-mail que não casa com nenhuma conta (nem por user_id/claim, "
                            + "e-mail ou conta do Jira). Rótulo: pmr ou squad_members.",
                    """
                            SELECT pmr.id AS id, LOWER(BTRIM(pmr.email)) AS email, pmr.project_id AS squad, 'pmr' AS detail
                            FROM project_member_roles pmr
                            WHERE pmr.email IS NOT NULL AND BTRIM(pmr.email) <> ''
                              AND NOT EXISTS (SELECT 1 FROM users u
                                              WHERE u.id = pmr.user_id
                                                 OR LOWER(BTRIM(u.email)) = LOWER(BTRIM(pmr.email))
                                                 OR (pmr.jira_account_id IS NOT NULL AND pmr.jira_account_id <> '' AND u.jira_account_id = pmr.jira_account_id))
                            UNION ALL
                            SELECT sm.db_id, LOWER(BTRIM(sm.email)), sm.squad_id, 'squad_members'
                            FROM squad_members sm
                            WHERE sm.email IS NOT NULL AND BTRIM(sm.email) <> ''
                              AND NOT EXISTS (SELECT 1 FROM users u
                                              WHERE u.id = sm.claimed_by_uid
                                                 OR LOWER(BTRIM(u.email)) = LOWER(BTRIM(sm.email))
                                                 OR u.jira_account_id = sm.jira_account_id)
                            """),
            new CategoryDef("PMR_UNRESOLVABLE",
                    "Linha pmr impossível de resolver",
                    "project_member_roles sem user_id, sem e-mail e sem conta do Jira: nenhuma conta chega a ela.",
                    """
                            SELECT pmr.id AS id, NULL::text AS email, pmr.project_id AS squad, pmr.role_key AS detail
                            FROM project_member_roles pmr
                            WHERE (pmr.email IS NULL OR BTRIM(pmr.email) = '')
                              AND (pmr.jira_account_id IS NULL OR pmr.jira_account_id = '')
                              AND pmr.user_id IS NULL
                            """),
            new CategoryDef("LEAD_USERS",
                    "Usuários com nível de acesso LEAD",
                    "Contas ativas com users.role = LEAD e em quantas squads elas têm liderança por vínculo "
                            + "(squads_lideranca=0 perderia todo o acesso quando o LEAD sair).",
                    PMR_U + """
                            SELECT u.id AS id, u.email AS email, u.squad_id AS squad,
                                   'squads_lideranca=' || (COUNT(DISTINCT p.project_up) FILTER (WHERE p.is_leadership))::text AS detail
                            FROM users u LEFT JOIN pmr_u p ON p.user_id = u.id
                            WHERE UPPER(u.role) = 'LEAD' AND u.is_active IS TRUE
                            GROUP BY u.id, u.email, u.squad_id
                            """),
            new CategoryDef("TRANSVERSAL_LEADERS",
                    "Liderança transversal (tribo/segmento)",
                    "Linhas de liderança TRIBE_LEAD, AGILE_COACH ou PEOPLE_LEAD, que expandem o acesso para toda a tribo/segmento "
                            + "da squad-âncora. O rótulo traz quantas squads cada uma alcança hoje.",
                    """
                            SELECT pmr.id AS id, pmr.email AS email, pmr.project_id AS squad,
                              'squads_alcancadas=' || (SELECT COUNT(*) FROM projects q
                                 WHERE (p.tribe_name IS NOT NULL AND LOWER(q.tribe_name) = LOWER(p.tribe_name))
                                    OR (p.segment_name IS NOT NULL AND LOWER(q.segment_name) = LOWER(p.segment_name)))::text AS detail
                            FROM project_member_roles pmr
                            JOIN projects p ON UPPER(p.id) = UPPER(pmr.project_id)
                            WHERE pmr.is_leadership AND UPPER(pmr.role_key) IN ('TRIBE_LEAD', 'AGILE_COACH', 'PEOPLE_LEAD')
                            """),
            new CategoryDef("SQUAD_KEY_CASE_VARIANTS",
                    "Chave de squad com variação de caixa/espaço",
                    "users.squad_id gravado de formas diferentes para a mesma squad (ex.: Compras x COMPRAS).",
                    """
                            SELECT UPPER(BTRIM(squad_id)) AS id, NULL::text AS email, UPPER(BTRIM(squad_id)) AS squad,
                                   COUNT(DISTINCT squad_id) || ' variantes' AS detail
                            FROM users
                            WHERE squad_id IS NOT NULL AND BTRIM(squad_id) <> ''
                            GROUP BY UPPER(BTRIM(squad_id))
                            HAVING COUNT(DISTINCT squad_id) > 1
                            """),
            new CategoryDef("USER_SQUAD_DEFAULT_DIVERGENT",
                    "users.squad_id diferente de default_project_id",
                    "Os dois campos de squad da conta apontam para squads diferentes (last-write-wins entre eles).",
                    """
                            SELECT u.id AS id, u.email AS email, u.squad_id AS squad, 'default=' || u.default_project_id AS detail
                            FROM users u
                            WHERE u.squad_id IS NOT NULL AND u.squad_id <> ''
                              AND u.default_project_id IS NOT NULL AND u.default_project_id <> ''
                              AND UPPER(u.squad_id) <> UPPER(u.default_project_id)
                            """),
            new CategoryDef("PMR_ORPHAN_PROJECT",
                    "Vínculo em squad que não existe em projects",
                    "project_member_roles de um project_id sem linha em projects (não há FK). O rótulo traz o número de linhas.",
                    """
                            SELECT pmr.project_id AS id, NULL::text AS email, pmr.project_id AS squad, COUNT(*) || ' linhas' AS detail
                            FROM project_member_roles pmr
                            LEFT JOIN projects p ON UPPER(p.id) = UPPER(pmr.project_id)
                            WHERE p.id IS NULL
                            GROUP BY pmr.project_id
                            """),
            new CategoryDef("ROSTER_ONLY_SQUAD",
                    "Squad que só existe no roster",
                    "squad_members de uma squad sem linha em projects: convite e login não criam vínculo nelas. O rótulo traz o número de pessoas.",
                    """
                            SELECT sm.squad_id AS id, NULL::text AS email, sm.squad_id AS squad, COUNT(*) || ' pessoas' AS detail
                            FROM squad_members sm
                            LEFT JOIN projects p ON UPPER(p.id) = UPPER(sm.squad_id)
                            WHERE p.id IS NULL
                            GROUP BY sm.squad_id
                            """),
            new CategoryDef("PROJECT_WITHOUT_TRIBE",
                    "Projeto sem tribo",
                    "projects.tribe_name vazio: a liderança de tribo não alcança essa squad.",
                    """
                            SELECT p.id AS id, NULL::text AS email, p.id AS squad, 'sem_tribo' AS detail
                            FROM projects p
                            WHERE p.tribe_name IS NULL OR BTRIM(p.tribe_name) = ''
                            """)
    );

    @Transactional(readOnly = true)
    public MembershipReportDto generate() {
        List<MembershipReportDto.Category> categories = new ArrayList<>();
        for (CategoryDef def : CATEGORIES) {
            categories.add(run(def));
        }
        if (authzDiff != null) {
            categories.add(wouldLoseAccess());
        }
        return new MembershipReportDto(Instant.now(), SAMPLE_LIMIT, totals(), categories);
    }

    static final String WOULD_LOSE_ACCESS = "WOULD_LOSE_ACCESS";

    /**
     * Etapa 2: quem o legado deixa acessar uma squad e a regra nova (só vínculos) NÃO deixaria. Calculada em Java com as
     * mesmas regras puras do modo sombra e do authz-diff ({@link AuthzDiffService}), não em SQL, porque precisa da
     * expansão por tribo/segmento e do roster por vaga/e-mail/conta do Jira. Uma linha por (pessoa, squad, ponto de
     * decisão); o rótulo é {@code ponto:motivo} (ex.: {@code matchesSquad:ONLY_ROSTER}).
     */
    private MembershipReportDto.Category wouldLoseAccess() {
        List<AuthzDiffService.Row> rows = authzDiff.wouldLoseAccess();
        Map<String, Long> breakdown = new LinkedHashMap<>();
        rows.stream().map(r -> r.check() + ":" + r.reason())
                .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(BREAKDOWN_LIMIT).forEach(e -> breakdown.put(e.getKey(), e.getValue()));
        List<MembershipReportDto.Row> sample = rows.stream()
                .sorted(java.util.Comparator.comparing((AuthzDiffService.Row r) -> r.squad() == null ? "" : r.squad())
                        .thenComparing(r -> r.email() == null ? "" : r.email())
                        .thenComparing(AuthzDiffService.Row::userId)
                        .thenComparing(AuthzDiffService.Row::check))
                .limit(SAMPLE_LIMIT)
                .map(r -> new MembershipReportDto.Row(r.userId(), r.email(), r.squad(), r.check() + ":" + r.reason()))
                .toList();
        return new MembershipReportDto.Category(WOULD_LOSE_ACCESS,
                "Perderia acesso quando a autorização passar só para os vínculos",
                "Pessoa que o legado deixa acessar a squad (users.squad_id, roster squad_members, cargo de liderança ou o fallback "
                        + "de squad sem liderança) e que a regra nova (app.membership.authz=new: só project_member_roles, ADMIN/LEAD) "
                        + "NEGARIA. Trate antes de virar a chave: crie o vínculo (convite, pedido de vínculo, importação do Jira) ou aceite a "
                        + "perda. Uma linha por pessoa, squad e ponto de decisão (matchesSquad = ler; canManageSquad = gerenciar; "
                        + "isSquadLeadership = liderança estrita). Rótulos: ONLY_ROSTER, ONLY_SQUAD_ID, ROSTER_AND_SQUAD_ID (acesso de leitura "
                        + "sem vínculo), ONLY_JOB_TITLE (gestão só pelo cargo), NO_LEADERSHIP_FALLBACK (gestão só porque a squad não tem "
                        + "liderança cadastrada), RESOLVER_ONLY (inconsistência entre os dois cálculos; investigar).",
                rows.size(), breakdown, sample, rows.size() > sample.size());
    }

    private MembershipReportDto.Category run(CategoryDef def) {
        String base = def.baseSql();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") q", Long.class);
        long total = count == null ? 0 : count;

        Map<String, Long> breakdown = new LinkedHashMap<>();
        if (total > 0) {
            jdbc.query("SELECT COALESCE(q.detail::text, '') AS d, COUNT(*) AS n FROM (" + base + ") q GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT "
                    + BREAKDOWN_LIMIT, rs -> {
                breakdown.put(rs.getString("d"), rs.getLong("n"));
            });
        }

        List<MembershipReportDto.Row> sample = total == 0 ? List.of() : jdbc.query(
                "SELECT q.id::text AS id, q.email::text AS email, q.squad::text AS squad, q.detail::text AS detail FROM (" + base
                        + ") q ORDER BY q.squad NULLS LAST, q.email NULLS LAST, q.id LIMIT " + SAMPLE_LIMIT,
                (rs, i) -> new MembershipReportDto.Row(rs.getString("id"), rs.getString("email"), rs.getString("squad"), rs.getString("detail")));

        return new MembershipReportDto.Category(def.code(), def.title(), def.description(), total, breakdown, sample, total > sample.size());
    }

    /** Contagens gerais para dimensionar as categorias. {@code pmr_source_LEGADO} = linhas anteriores à coluna source. */
    private Map<String, Long> totals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("users_total", count("SELECT COUNT(*) FROM users"));
        totals.put("users_active", count("SELECT COUNT(*) FROM users WHERE is_active IS TRUE"));
        totals.put("projects", count("SELECT COUNT(*) FROM projects"));
        totals.put("pmr_rows", count("SELECT COUNT(*) FROM project_member_roles"));
        totals.put("pmr_with_user_id", count("SELECT COUNT(*) FROM project_member_roles WHERE user_id IS NOT NULL"));
        totals.put("pmr_without_user_id", count("SELECT COUNT(*) FROM project_member_roles WHERE user_id IS NULL"));
        totals.put("pmr_leadership_rows", count("SELECT COUNT(*) FROM project_member_roles WHERE is_leadership"));
        jdbc.query("SELECT COALESCE(source, 'LEGADO') AS s, COUNT(*) AS n FROM project_member_roles GROUP BY 1 ORDER BY 1",
                rs -> { totals.put("pmr_source_" + rs.getString("s"), rs.getLong("n")); });
        totals.put("squad_members_rows", count("SELECT COUNT(*) FROM squad_members"));
        totals.put("squad_members_claimed", count("SELECT COUNT(*) FROM squad_members WHERE claimed_by_uid IS NOT NULL"));
        totals.put("squad_member_exclusions", count("SELECT COUNT(*) FROM squad_member_exclusions"));
        return totals;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    // ---------------- CSV ----------------

    /**
     * Mesmo conteúdo do JSON em CSV (separador ponto e vírgula, para abrir direto no Excel em pt-BR; UTF-8 com BOM).
     * Colunas: tipo;categoria;titulo;total;rotulo_qtd;id;email;squad;detalhe. Linhas "TOTAL" trazem as contagens gerais;
     * "CATEGORIA" traz o total por categoria; "ROTULO" a quebra por rótulo; "AMOSTRA" cada ocorrência da amostra.
     * Células que começam com = + - @ ganham um apóstrofo (injeção de fórmula no Excel).
     */
    public String toCsv(MembershipReportDto report) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("tipo;categoria;titulo;total;rotulo_qtd;id;email;squad;detalhe\r\n");
        report.totals().forEach((k, v) -> line(sb, "TOTAL", k, "", String.valueOf(v), "", "", "", "", ""));
        for (MembershipReportDto.Category c : report.categories()) {
            line(sb, "CATEGORIA", c.code(), c.title(), String.valueOf(c.count()), "", "", "", "", "");
            c.breakdown().forEach((label, n) -> line(sb, "ROTULO", c.code(), "", "", String.valueOf(n), "", "", "", label));
            for (MembershipReportDto.Row r : c.sample()) {
                line(sb, "AMOSTRA", c.code(), "", "", "", r.id(), r.email(), r.squad(), r.detail());
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(csv(cells[i]));
        }
        sb.append("\r\n");
    }

    static String csv(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') v = "'" + v;
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
