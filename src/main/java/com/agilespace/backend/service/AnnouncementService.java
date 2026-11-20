package com.agilespace.backend.service;

import com.agilespace.backend.domain.AnnouncementDismissal;
import com.agilespace.backend.domain.GlobalAnnouncement;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.dto.AnnouncementDto;
import com.agilespace.backend.dto.AnnouncementRequest;
import com.agilespace.backend.repository.AnnouncementDismissalRepository;
import com.agilespace.backend.repository.GlobalAnnouncementRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * Avisos v2 (docs/home-api.md §1): tipo, público, vigência, link e dispensa por usuário.
 * O público de cada pessoa sai do {@link SquadAudienceService} (squads acessíveis + papéis), o mesmo usado para
 * notificar: quem recebe a notificação ANNOUNCEMENT é quem vê o aviso.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    static final int MAX_TITLE = 120;
    static final int MAX_CONTENT = 2000;
    static final int MAX_LINK = 500;
    static final int MAX_AUDIENCE_ITEMS = 100;
    static final int MAX_HISTORY = 100;

    static final List<String> TYPES = List.of("MAINTENANCE", "IMPORTANT", "INFO"); // também a ordem de exibição
    static final Set<String> AUDIENCES = Set.of("ALL", "SQUADS", "ROLES");

    private final GlobalAnnouncementRepository announcementRepository;
    private final AnnouncementDismissalRepository dismissalRepository;
    private final UserRepository userRepository;
    private final SquadAudienceService audienceService;
    private final NotificationTriggers notificationTriggers;

    // --- rotas do usuário ---

    /** Vigentes, do público do usuário e não dispensados. MAINTENANCE, IMPORTANT, INFO; depois createdAt desc. */
    @Transactional(readOnly = true)
    public List<AnnouncementDto> active(String userId, String role) {
        SquadAudienceService.UserContext ctx = requireContext(userId, role);
        Set<String> dismissed = dismissalRepository.findDismissedIds(userId);
        List<GlobalAnnouncement> list = announcementRepository.findActive(ServerTime.now()).stream()
                .filter(audienceOf(ctx))
                .filter(a -> !dismissed.contains(a.getId()))
                .sorted(Comparator.comparingInt((GlobalAnnouncement a) -> typeRank(a.getType()))
                        .thenComparing(GlobalAnnouncement::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return toDtos(list, dismissed);
    }

    /** Já começaram a valer (inclui expirados e dispensados, com {@code dismissed}), createdAt desc. */
    @Transactional(readOnly = true)
    public List<AnnouncementDto> history(String userId, String role, Integer limit) {
        SquadAudienceService.UserContext ctx = requireContext(userId, role);
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, MAX_HISTORY);
        Set<String> dismissed = dismissalRepository.findDismissedIds(userId);
        List<GlobalAnnouncement> list = announcementRepository.findStarted(ServerTime.now()).stream()
                .filter(audienceOf(ctx))
                .limit(size)
                .toList();
        return toDtos(list, dismissed);
    }

    /** Idempotente. 404 se o aviso não existe ou não é do público do usuário. */
    @Transactional
    public void dismiss(String userId, String role, String announcementId) {
        SquadAudienceService.UserContext ctx = requireContext(userId, role);
        GlobalAnnouncement a = announcementRepository.findById(announcementId)
                .filter(audienceOf(ctx))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aviso não encontrado."));
        AnnouncementDismissal.Key key = new AnnouncementDismissal.Key(a.getId(), userId);
        if (!dismissalRepository.existsById(key)) {
            dismissalRepository.save(AnnouncementDismissal.builder()
                    .announcementId(a.getId()).userId(userId).dismissedAt(ServerTime.now()).build());
        }
    }

    // --- rota pública (antes do login) ---

    /** Compatível com o endpoint antigo: só público ALL e vigentes. */
    @Transactional(readOnly = true)
    public List<AnnouncementDto> publicActive() {
        List<GlobalAnnouncement> list = announcementRepository.findActive(ServerTime.now()).stream()
                .filter(a -> "ALL".equals(a.getAudienceType()))
                .toList();
        return toDtos(list, Set.of());
    }

    // --- admin ---

    @Transactional(readOnly = true)
    public List<AnnouncementDto> adminList() {
        return toDtos(announcementRepository.findByOrderByCreatedAtDesc(), Set.of());
    }

    /**
     * @param createdBy  autoria gravada (e-mail do token, senão userId) — nunca do corpo
     * @param actorUserId quem publicou (não recebe a própria notificação)
     */
    @Transactional
    public AnnouncementDto create(AnnouncementRequest request, String createdBy, String actorUserId) {
        if (createdBy == null || createdBy.isBlank()) {
            throw badRequest("Autor do aviso não identificado.");
        }
        LocalDateTime now = ServerTime.now();
        GlobalAnnouncement a = GlobalAnnouncement.builder()
                .id(UUID.randomUUID().toString())
                .createdBy(createdBy)
                .createdAt(now)
                .build();
        apply(a, request, now);
        GlobalAnnouncement saved = announcementRepository.save(a);
        publishNotifications(saved, actorUserId);
        return toDtos(List.of(saved), Set.of()).get(0);
    }

    @Transactional
    public AnnouncementDto update(String id, AnnouncementRequest request, String actorUserId) {
        GlobalAnnouncement a = announcementRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aviso não encontrado."));
        apply(a, request, a.getCreatedAt() != null ? a.getCreatedAt() : ServerTime.now());
        GlobalAnnouncement saved = announcementRepository.save(a);
        // Quem já foi notificado deste aviso não recebe de novo (chave por aviso); só entra quem passou a ser público.
        publishNotifications(saved, actorUserId);
        return toDtos(List.of(saved), Set.of()).get(0);
    }

    @Transactional
    public void delete(String id) {
        if (!announcementRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aviso não encontrado.");
        }
        announcementRepository.deleteById(id);
    }

    // --- regras ---

    /** O aviso é do público deste usuário? ALL; SQUADS = alguma squad acessível; ROLES = nível de conta (ADMIN/MEMBER), roleKey ou a chave derivada LEADERSHIP. */
    static Predicate<GlobalAnnouncement> audienceOf(SquadAudienceService.UserContext ctx) {
        return a -> switch (a.getAudienceType() == null ? "ALL" : a.getAudienceType()) {
            case "SQUADS" -> a.getAudienceSquadIds() != null && a.getAudienceSquadIds().stream().anyMatch(ctx::hasSquad);
            case "ROLES" -> a.getAudienceRoles() != null && a.getAudienceRoles().stream()
                    .anyMatch(r -> r != null && ctx.roleKeys().contains(r.toUpperCase(Locale.ROOT)));
            default -> true;
        };
    }

    private void apply(GlobalAnnouncement a, AnnouncementRequest r, LocalDateTime defaultValidFrom) {
        if (r == null) throw badRequest("Corpo inválido.");
        String title = r.title() == null ? "" : r.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE) throw badRequest("O título deve ter de 1 a " + MAX_TITLE + " caracteres.");
        String content = r.content() == null ? "" : r.content().trim();
        if (content.isEmpty() || content.length() > MAX_CONTENT) throw badRequest("O texto deve ter de 1 a " + MAX_CONTENT + " caracteres.");

        String type = r.type() == null || r.type().isBlank() ? "INFO" : r.type().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw badRequest("Tipo inválido: use INFO, IMPORTANT ou MAINTENANCE.");
        String audience = r.audienceType() == null || r.audienceType().isBlank() ? "ALL" : r.audienceType().trim().toUpperCase(Locale.ROOT);
        if (!AUDIENCES.contains(audience)) throw badRequest("Público inválido: use ALL, SQUADS ou ROLES.");

        Set<String> squads = cleanList(r.audienceSquadIds(), 100, false, "squad");
        Set<String> roles = cleanList(r.audienceRoles(), 50, true, "papel");
        // Etapa 4: LEAD deixou de ser nível de conta; o público "liderança" é a chave derivada LEADERSHIP (cliente antigo ainda manda LEAD).
        if (roles.remove("LEAD")) roles.add(SquadAudienceService.LEADERSHIP_KEY);
        if ("SQUADS".equals(audience) && squads.isEmpty()) throw badRequest("Escolha ao menos uma squad.");
        if ("ROLES".equals(audience) && roles.isEmpty()) throw badRequest("Escolha ao menos um papel.");

        String link = r.link() == null || r.link().isBlank() ? null : r.link().trim();
        if (link != null && (link.length() > MAX_LINK || !isAllowedLink(link))) {
            throw badRequest("Link inválido: use http(s):// ou uma rota interna começando com /.");
        }

        LocalDateTime validFrom = r.validFrom() != null ? ServerTime.toLocal(r.validFrom()) : defaultValidFrom;
        LocalDateTime validUntil = r.validUntil() != null ? ServerTime.toLocal(r.validUntil()) : null;
        if (validUntil != null && !validUntil.isAfter(validFrom)) throw badRequest("O fim da vigência deve ser depois do início.");

        a.setTitle(title);
        a.setContent(content);
        a.setType(type);
        a.setAudienceType(audience);
        a.setAudienceSquadIds(new LinkedHashSet<>("SQUADS".equals(audience) ? squads : Set.of()));
        a.setAudienceRoles(new LinkedHashSet<>("ROLES".equals(audience) ? roles : Set.of()));
        a.setLink(link);
        a.setValidFrom(validFrom);
        a.setValidUntil(validUntil);
    }

    static boolean isAllowedLink(String link) {
        String lower = link.toLowerCase(Locale.ROOT);
        if (link.startsWith("/")) {
            return !link.startsWith("//") && !link.contains("\\");
        }
        return (lower.startsWith("https://") || lower.startsWith("http://")) && !link.contains(" ");
    }

    private static Set<String> cleanList(List<String> values, int maxLength, boolean upper, String label) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        if (values.size() > MAX_AUDIENCE_ITEMS) throw badRequest("Lista de " + label + " grande demais.");
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            String t = v.trim();
            if (t.length() > maxLength) throw badRequest("Valor de " + label + " inválido.");
            out.add(upper ? t.toUpperCase(Locale.ROOT) : t);
        }
        return out;
    }

    private void publishNotifications(GlobalAnnouncement a, String actorUserId) {
        notificationTriggers.announcementPublished(a.getId(), a.getTitle(), a.getAudienceType(),
                List.copyOf(a.getAudienceSquadIds()), List.copyOf(a.getAudienceRoles()),
                a.getValidFrom(), a.getValidUntil(), a.getLink(), actorUserId);
    }

    private SquadAudienceService.UserContext requireContext(String userId, String role) {
        return audienceService.contextFor(userId, role)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida."));
    }

    private List<AnnouncementDto> toDtos(List<GlobalAnnouncement> list, Set<String> dismissed) {
        Map<String, String> names = authorNames(list);
        return list.stream().map(a -> new AnnouncementDto(
                a.getId(), a.getTitle(), a.getContent(), a.getType(), a.getAudienceType(),
                List.copyOf(a.getAudienceSquadIds() == null ? Set.of() : a.getAudienceSquadIds()),
                List.copyOf(a.getAudienceRoles() == null ? Set.of() : a.getAudienceRoles()),
                ServerTime.toInstant(a.getValidFrom() != null ? a.getValidFrom() : a.getCreatedAt()),
                ServerTime.toInstant(a.getValidUntil()), a.getLink(), a.getCreatedBy(),
                a.getCreatedBy() == null ? null : names.get(a.getCreatedBy().toLowerCase(Locale.ROOT)),
                ServerTime.toInstant(a.getCreatedAt()), dismissed.contains(a.getId()))).toList();
    }

    /** createdBy guarda o e-mail de quem publicou (ou o userId, em avisos antigos/sem e-mail). */
    private Map<String, String> authorNames(List<GlobalAnnouncement> list) {
        Set<String> emails = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (GlobalAnnouncement a : list) {
            String by = a.getCreatedBy();
            if (by == null || by.isBlank()) continue;
            if (by.contains("@")) emails.add(by.trim().toLowerCase(Locale.ROOT));
            else ids.add(by.trim());
        }
        Map<String, String> names = new HashMap<>();
        if (!emails.isEmpty()) {
            for (User u : userRepository.findActiveByEmails(emails)) {
                names.put(u.getEmail().trim().toLowerCase(Locale.ROOT), u.getName());
            }
        }
        if (!ids.isEmpty()) {
            for (User u : userRepository.findAllById(ids)) {
                names.put(u.getId().toLowerCase(Locale.ROOT), u.getName());
            }
        }
        return names;
    }

    private static int typeRank(String type) {
        int i = TYPES.indexOf(type);
        return i < 0 ? TYPES.size() : i;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }



}
