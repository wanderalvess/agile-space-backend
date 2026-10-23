package com.agilespace.backend.service;

import com.agilespace.backend.dto.JiraTextSourcesRequest;
import com.agilespace.backend.dto.JiraTextSourcesRequest.Comment;
import com.agilespace.backend.dto.JiraTextSourcesRequest.Issue;
import com.agilespace.backend.dto.JiraTextSourcesResponse;
import com.agilespace.backend.dto.JiraTextSourcesResponse.Field;
import com.agilespace.backend.dto.JiraTextSourcesResponse.IssueSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JiraTextSourceService - de onde vem cada texto importado do Jira")
class JiraTextSourceServiceTest {

    private final JiraTextSourceService service = new JiraTextSourceService();

    private static Issue issue(String key, String description, Comment... comments) {
        return new Issue(key, "História", "Título " + key, description, null, List.of(comments), null, null);
    }

    private static Issue withCriteria(String key, String description, String criteria) {
        return new Issue(key, "História", "Título " + key, description, criteria, List.of(), null, null);
    }

    private static Issue subtask(String key, String type, String description) {
        return new Issue(key, type, "Sub " + key, description, null, List.of(), null, null);
    }

    private static Issue withRelated(Issue base, List<Issue> subtasks, Issue parent) {
        return new Issue(base.key(), base.type(), base.title(), base.description(), base.criteriaField(), base.comments(), subtasks, parent);
    }

    private static Comment comment(String body, String author, String created) {
        return new Comment(body, author, created);
    }

    private IssueSources analyze(Issue issue) {
        return service.analyzeIssue(issue);
    }

    private static String value(Field f) {
        return JiraTextSourceService.value(f);
    }

    @Test
    @DisplayName("Título explícito no comentário vira Solução certa, com o autor e a data na fonte")
    void explicitCommentSection() {
        IssueSources s = analyze(issue("PTV-1", "O painel não mostra a capacidade.",
                comment("Solução:\nMostrar a capacidade por pessoa.", "Carla Menezes", "2026-10-14T10:00:00.000-0300")));

        assertEquals("certa", s.solution().confidence());
        assertTrue(value(s.solution()).contains("Mostrar a capacidade por pessoa"));
        assertEquals("Comentário de Carla Menezes, 14/10", s.solution().candidates().get(0).label());
    }

    @Test
    @DisplayName("Descrição sem título 'Problema' gera só um palpite, que não preenche o campo")
    void preambleIsOnlyAGuess() {
        IssueSources s = analyze(issue("PTV-2", "O painel não mostra quantas horas a squad tem livres."));

        assertEquals("revisar", s.problem().confidence());
        assertNull(s.problem().sourceId());
        assertEquals("", value(s.problem()));
        assertFalse(s.problem().candidates().get(0).explicit());
    }

    @Test
    @DisplayName("Título 'Problema:' e 'Solução:' na descrição entram certos")
    void headersInDescription() {
        IssueSources s = analyze(issue("PTV-3", "Problema:\nA fila é montada à mão.\nSolução:\nOrdenar pela prioridade."));

        assertEquals("certa", s.problem().confidence());
        assertTrue(value(s.problem()).contains("montada à mão"));
        assertEquals("certa", s.solution().confidence());
        assertTrue(value(s.solution()).contains("Ordenar pela prioridade"));
    }

    @Test
    @DisplayName("Emoji e negrito antes do título ('📝 Contexto', '*Solução:*') ainda são reconhecidos")
    void decoratedHeaders() {
        IssueSources s = analyze(issue("PTV-4", "📝 Contexto\nTexto do contexto.\n*Solução:*\nTexto da solução."));

        assertEquals("certa", s.problem().confidence());
        assertTrue(value(s.problem()).contains("Texto do contexto"));
        assertTrue(value(s.solution()).contains("Texto da solução"));
    }

    @Test
    @DisplayName("Dois comentários com a mesma seção e textos diferentes pedem revisão e o campo fica vazio")
    void conflictBetweenComments() {
        IssueSources s = analyze(issue("PTV-5", "",
                comment("Solução:\nPrimeira versão.", "Ana", "2026-10-10T09:00:00.000-0300"),
                comment("Solução:\nVersão corrigida.", "Bia", "2026-10-12T09:00:00.000-0300")));

        assertEquals("revisar", s.solution().confidence());
        assertNull(s.solution().sourceId());
        assertEquals(2, s.solution().candidates().size());
        assertEquals("", value(s.solution()));
    }

    @Test
    @DisplayName("O mesmo texto em duas fontes conta uma vez e não vira conflito")
    void sameTextTwiceIsNotAConflict() {
        IssueSources s = analyze(issue("PTV-6", "Solução:\nMesmo texto.",
                comment("Solução:\nMesmo   texto.", "Ana", "2026-10-10T09:00:00.000-0300")));

        assertEquals("certa", s.solution().confidence());
        assertEquals(1, s.solution().candidates().size());
    }

    @Test
    @DisplayName("Campo de critérios do Jira é certo; sem ele, linhas em Gherkin são palpite")
    void criteriaFieldAndGuess() {
        IssueSources withField = analyze(withCriteria("PTV-7", "", "A capacidade muda com as férias."));
        assertEquals("certa", withField.acceptanceCriteria().confidence());
        assertEquals("campo", withField.acceptanceCriteria().candidates().get(0).kind());

        IssueSources guess = analyze(issue("PTV-8", "Dado que há férias\nQuando abro o painel\nEntão vejo a capacidade menor"));
        assertEquals("revisar", guess.acceptanceCriteria().confidence());
        assertEquals("", value(guess.acceptanceCriteria()));
    }

    @Test
    @DisplayName("Sem texto nenhum, os três campos ficam vazios")
    void nothingAtAll() {
        IssueSources s = analyze(issue("PTV-9", ""));

        assertEquals("vazio", s.problem().confidence());
        assertEquals("vazio", s.solution().confidence());
        assertEquals("vazio", s.acceptanceCriteria().confidence());
    }

    @Test
    @DisplayName("Subtarefa de codificação preenche o campo quando a issue só tem palpite")
    void subtaskFillsWhenOnlyGuess() {
        Issue base = issue("PTV-10", "Texto solto da história.");
        Issue sub = subtask("PTV-11", "Codificação", "Solução:\nAlteração feita na rotina.");

        IssueSources s = analyze(withRelated(base, List.of(sub), null));

        assertEquals("certa", s.solution().confidence());
        assertEquals("subtarefa", s.solution().candidates().get(0).kind());
        assertEquals("Subtarefa · PTV-11", s.solution().candidates().get(0).label());
    }

    @Test
    @DisplayName("Subtarefa não briga com texto explícito que a própria issue já tem")
    void subtaskIgnoredWhenOwnTextIsExplicit() {
        Issue base = issue("PTV-12", "Solução:\nTexto da história.");
        Issue sub = subtask("PTV-13", "Codificação", "Solução:\nOutro texto da subtarefa.");

        IssueSources s = analyze(withRelated(base, List.of(sub), null));

        assertEquals("certa", s.solution().confidence());
        assertEquals(1, s.solution().candidates().size());
        assertTrue(value(s.solution()).contains("Texto da história"));
    }

    @Test
    @DisplayName("Entre as subtarefas, as de codificação valem sozinhas quando existem")
    void devSubtasksWinOverTestSubtasks() {
        Issue base = issue("PTV-14", "");
        Issue test = subtask("PTV-15", "Teste", "Solução:\nTexto da subtarefa de teste.");
        Issue dev = subtask("PTV-16", "Codificação", "Solução:\nTexto da subtarefa de codificação.");

        IssueSources s = analyze(withRelated(base, List.of(test, dev), null));

        assertEquals("certa", s.solution().confidence());
        assertTrue(value(s.solution()).contains("codificação"));
    }

    @Test
    @DisplayName("Issue pai entra como fonte quando a subtarefa importada não tem o texto")
    void parentFillsWhenSubtaskHasNothing() {
        Issue parent = new Issue("PTV-20", "História", "Pai", "Problema:\nA fila é montada à mão.", null, List.of(), null, null);
        Issue sub = new Issue("PTV-21", "Codificação", "Cod", "", null, List.of(), null, parent);

        IssueSources s = analyze(sub);

        assertEquals("certa", s.problem().confidence());
        assertEquals("pai", s.problem().candidates().get(0).kind());
        assertEquals("Issue pai · PTV-20", s.problem().candidates().get(0).label());
    }

    @Test
    @DisplayName("analyze devolve uma entrada por issue, na ordem do pedido")
    void analyzeKeepsOrder() {
        JiraTextSourcesResponse response = service.analyze(new JiraTextSourcesRequest(List.of(
                issue("B-2", ""), issue("A-1", "Problema:\nTexto."))));

        assertEquals(List.of("B-2", "A-1"), response.issues().stream().map(IssueSources::key).toList());
    }

    @Test
    @DisplayName("stripHtml tira tags, decodifica entidades e a marcação wiki, e guarda as quebras de linha")
    void stripHtmlBasics() {
        assertEquals("Linha 1\nLinha 2 & mais", JiraTextSourceService.stripHtml("<p>Linha 1</p><p>Linha 2 &amp; mais</p>"));
        assertEquals("Título\nTexto em negrito", JiraTextSourceService.stripHtml("h2. Título\nTexto em *negrito*"));
        assertEquals("", JiraTextSourceService.stripHtml(null));
    }

    @Test
    @DisplayName("shortDate mostra dia/mês como o Jira escreveu, sem converter fuso")
    void shortDateAsWritten() {
        assertEquals("14/10", JiraTextSourceService.shortDate("2026-10-14T23:59:00.000-0300"));
        assertEquals("", JiraTextSourceService.shortDate("lixo"));
        assertEquals("Comentário", JiraTextSourceService.commentLabel(null, null));
    }

    @Test
    @DisplayName("Texto enorme com muitos cabeçalhos termina sem travar")
    void manyHeadersTerminates() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("Solução:\nlinha ").append(i).append('\n');
        long start = System.nanoTime();
        IssueSources s = analyze(issue("PTV-30", sb.toString()));
        assertNotNull(s.solution());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "a extração demorou mais de 5 s");
    }
}
