package com.agilespace.backend.mcp;

import com.agilespace.backend.domain.ApiKeyScope;
import com.agilespace.backend.domain.Prompt;
import com.agilespace.backend.domain.PromptCollection;
import com.agilespace.backend.service.PromptService;
import com.agilespace.backend.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ferramentas MCP do Prompt Hub (iniciativas, prompts, gems, skills).
 * Leitura: listPrompts, getPrompt, listPromptCollections, getPromptCollection (PROMPTHUB_READ).
 * Escrita: importSkill, batchImportSkills (PROMPTHUB_WRITE) para ingestão automatizada por IAs.
 */
@Component
@RequiredArgsConstructor
public class McpPromptHubTools {

    private final PromptService promptService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Tool(description = "Lista prompts/iniciativas do Prompt Hub (público), com busca textual opcional ou filtro por autor")
    public PromptPage listPrompts(
            @ToolParam(description = "Termo de busca livre (título/descrição/conteúdo); vazio lista os mais recentes públicos", required = false) String query,
            @ToolParam(description = "Filtra por id do autor, opcional", required = false) String authorId,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.PROMPTHUB_READ);
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        // Page<T> não é suportado como retorno de @Tool pelo Spring AI (vira "functional
        // type" e o método é ignorado no registro sem erro) — achatamos pro record abaixo.
        Page<Prompt> result = promptService.listPublicPrompts(query, authorId, PageRequest.of(p, s));
        return new PromptPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record PromptPage(java.util.List<Prompt> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca um prompt/iniciativa do Prompt Hub pelo id")
    public Prompt getPrompt(@ToolParam(description = "UUID do prompt") String id, ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.PROMPTHUB_READ);
        // Não distingue inexistente de privado — mesma mensagem de erro nos dois casos,
        // pra não confirmar pra quem tem só a API key que um id privado existe.
        Prompt prompt = promptService.getPromptById(UUID.fromString(id));
        if (!"public".equals(prompt.getVisibility())) {
            throw new IllegalArgumentException("Prompt not found with id: " + id);
        }
        return prompt;
    }

    @Tool(description = "Lista coleções públicas do Prompt Hub (trilhas de prompts), filtro opcional por dono")
    @Transactional(readOnly = true)
    public CollectionPage listPromptCollections(
            @ToolParam(description = "Filtra por id do dono, opcional", required = false) String ownerId,
            @ToolParam(description = "Página, 0-based (padrão 0)", required = false) Integer page,
            @ToolParam(description = "Tamanho da página (padrão 20)", required = false) Integer size,
            ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.PROMPTHUB_READ);
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        // Visibilidade sempre fixa em "public" — antes aceitava um parâmetro livre
        // ("public"/"private") que deixava qualquer dono de API key listar TODAS as
        // coleções privadas do sistema (promptService.listCollections("private", null, ...)
        // sem filtro de dono nenhum). Removido, não só ignorado, pra não reabrir por engano.
        Page<PromptCollection> result = promptService.listCollections("public", ownerId, PageRequest.of(p, s));
        // Cada coleção pública ainda carrega seus items via @ManyToMany — sem esse filtro
        // um item privado dentro de coleção pública vazava aqui (só getPromptCollection
        // filtrava antes; a listagem serializa os items direto).
        result.getContent().forEach(collection -> collection.setItems(
                collection.getItems().stream()
                        .filter(p2 -> "public".equals(p2.getVisibility()))
                        .collect(java.util.stream.Collectors.toList())));
        return new CollectionPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
    }

    public record CollectionPage(java.util.List<PromptCollection> content, long totalElements, int page, int size) {
    }

    @Tool(description = "Busca uma coleção pública do Prompt Hub pelo id, com os itens (prompts públicos) embutidos")
    @Transactional(readOnly = true)
    public PromptCollection getPromptCollection(@ToolParam(description = "UUID da coleção") String id, ToolContext toolContext) {
        ApiKeyContext.from(toolContext).requireScope(ApiKeyScope.PROMPTHUB_READ);
        // items é @ManyToMany lazy; a serialização do resultado do @Tool roda fora da
        // sessão Hibernate original (mesmo achado do McpSquadTools sobre thread-local
        // perdido no transporte SSE), então forçamos o fetch aqui dentro, ainda com a
        // transação aberta, em vez de deixar a lazy list estourar na hora de virar JSON.
        PromptCollection collection = promptService.getCollectionById(UUID.fromString(id));
        if (!"public".equals(collection.getVisibility())) {
            throw new IllegalArgumentException("Collection not found with id: " + id);
        }
        Hibernate.initialize(collection.getItems());
        // Item privado dentro de uma coleção pública é filtrado, não derruba a resposta
        // inteira (a coleção em si já passou pelo filtro de público acima).
        collection.setItems(collection.getItems().stream()
                .filter(p -> "public".equals(p.getVisibility()))
                .collect(java.util.stream.Collectors.toList()));
        return collection;
    }

    public record BatchImportResult(int totalImported, List<String> importedTitles) {
    }

    public record BatchSkillDto(String name, String content, String description, String tags, String visibility) {
    }

    @Tool(description = "Importa ou atualiza uma skill (formato Agent Skills / SKILL.md) no Prompt Hub")
    public Prompt importSkill(
            @ToolParam(description = "Nome/título da skill (ex: map-java-project); se omitido, extrai do frontmatter", required = false) String name,
            @ToolParam(description = "Conteúdo Markdown completo da skill, preferencialmente com frontmatter YAML delimitado por ---") String content,
            @ToolParam(description = "Descrição resumida da skill (opcional, extrai do frontmatter)", required = false) String description,
            @ToolParam(description = "Tags separadas por vírgula (ex: java, spring, testes)", required = false) String tags,
            @ToolParam(description = "Visibilidade: 'public' (padrão) ou 'private'", required = false) String visibility,
            ToolContext toolContext) {
        ApiKeyContext ctx = ApiKeyContext.from(toolContext);
        ctx.requireScope(ApiKeyScope.PROMPTHUB_WRITE);

        Prompt skillPrompt = buildSkillPrompt(ctx, name, content, description, tags, visibility);
        Prompt saved = promptService.saveOrUpdateSkill(skillPrompt);
        Hibernate.initialize(saved.getTags());
        return saved;
    }

    @Tool(description = "Importa múltiplas skills em lote para o Prompt Hub a partir de um JSON array de objetos {name, content, description, tags, visibility}")
    public BatchImportResult batchImportSkills(
            @ToolParam(description = "Array JSON contendo as skills a importar") String skillsJson,
            ToolContext toolContext) {
        ApiKeyContext ctx = ApiKeyContext.from(toolContext);
        ctx.requireScope(ApiKeyScope.PROMPTHUB_WRITE);

        List<BatchSkillDto> items;
        try {
            items = objectMapper.readValue(skillsJson, new TypeReference<List<BatchSkillDto>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Erro ao processar JSON de skills: " + e.getMessage(), e);
        }

        List<String> imported = new ArrayList<>();
        for (BatchSkillDto item : items) {
            Prompt p = buildSkillPrompt(ctx, item.name(), item.content(), item.description(), item.tags(), item.visibility());
            Prompt saved = promptService.saveOrUpdateSkill(p);
            imported.add(saved.getTitle());
        }

        return new BatchImportResult(imported.size(), imported);
    }

    private Prompt buildSkillPrompt(ApiKeyContext ctx, String name, String content, String description, String tags, String visibility) {
        String resolvedName = (name != null && !name.isBlank()) ? name : extractFrontmatterField(content, "name");
        if (resolvedName == null || resolvedName.isBlank()) {
            resolvedName = "Nova Skill";
        }

        String resolvedDesc = (description != null && !description.isBlank()) ? description : extractFrontmatterField(content, "description");

        String authorId = ctx.ownerUserIdOrFallback("mcp-agent");
        String authorName = "Agente MCP";
        String authorRole = "AI Assistant";
        String authorSquad = ctx.squadId();
        String authorAvatar = null;

        if (ctx.ownerUserId() != null) {
            var userOpt = userRepository.findById(ctx.ownerUserId());
            if (userOpt.isPresent()) {
                var u = userOpt.get();
                if (u.getName() != null && !u.getName().isBlank()) authorName = u.getName();
                if (u.getRole() != null && !u.getRole().isBlank()) authorRole = u.getRole();
                if (authorSquad == null && u.getSquadId() != null) authorSquad = u.getSquadId();
                authorAvatar = u.getAvatarUrl();
            }
        }

        return Prompt.builder()
                .title(resolvedName)
                .description(resolvedDesc)
                .content(content)
                .type("skill")
                .visibility((visibility != null && !visibility.isBlank()) ? visibility : "public")
                .status("producao")
                .impact("medio")
                .authorId(authorId)
                .authorName(authorName)
                .authorRole(authorRole)
                .authorSquad(authorSquad)
                .authorAvatar(authorAvatar)
                .tags(parseTags(tags))
                .build();
    }

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^\\s*---\\r?\\n([\\s\\S]*?)\\r?\\n---", Pattern.MULTILINE);

    private static String extractFrontmatterField(String content, String field) {
        if (content == null) return null;
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) return null;
        String block = matcher.group(1);
        String[] lines = block.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fieldMatcher = Pattern.compile("^" + Pattern.quote(field) + "\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (fieldMatcher.find()) {
                String val = fieldMatcher.group(1).trim().replaceAll("^[\"']|[\"']$", "");
                if (val.equals(">-") || val.equals(">") || val.equals("|") || val.equals("|-") || val.isEmpty()) {
                    List<String> multiline = new ArrayList<>();
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextLine = lines[j];
                        if (nextLine.matches("^\\s{2,}.*")) {
                            multiline.add(nextLine.trim());
                        } else if (nextLine.trim().isEmpty()) {
                            // linha em branco permitida no bloco
                        } else {
                            break;
                        }
                    }
                    if (!multiline.isEmpty()) {
                        return String.join(" ", multiline).trim();
                    }
                }
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    private static Set<String> parseTags(String tags) {
        Set<String> result = new HashSet<>();
        result.add("skill");
        if (tags != null && !tags.isBlank()) {
            for (String tag : tags.split(",")) {
                String trimmed = tag.trim().replace("#", "").toLowerCase();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }
}
