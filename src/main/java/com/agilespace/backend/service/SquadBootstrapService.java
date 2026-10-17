package com.agilespace.backend.service;

import com.agilespace.backend.domain.ProjectConfig;
import com.agilespace.backend.domain.ProjectMemberRole;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.repository.ProjectConfigRepository;
import com.agilespace.backend.repository.ProjectMemberRoleRepository;
import com.agilespace.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Bootstrap de squad nova em ambiente limpo (antes embutido em {@code SquadController.bootstrapNewSquad}): a primeira
 * pessoa sem squad que escreve numa squad que ainda não existe (nem configuração, nem pessoas) passa a pertencer a ela.
 *
 * <p>Em {@code app.membership.authz=new} isso só acontece com {@code app.membership.bootstrap=on} (padrão {@code off}):
 * qualquer conta sem vínculo poderia inventar uma chave de squad e virar Agile Master (e, por isso, gerir equipes em
 * todo o sistema). Com {@code off} o bootstrap não concede nada em {@code new}: a squad vazia é 403 para quem não é ADMIN
 * (o primeiro ADMIN cria a equipe por {@code POST /api/projects}; os demais entram por convite, pedido de vínculo ou
 * importação).
 *
 * <p>Em {@code app.membership.authz=legacy|shadow} o comportamento é o de sempre: grava {@code users.squad_id} (e
 * {@code default_project_id}, se vazio) na pessoa. Em {@code new} isso não bastaria (squad_id solto não é vínculo e a
 * pessoa seria negada na chamada seguinte), então o bootstrap TAMBÉM cria o vínculo: o projeto (se faltar) e a linha de
 * {@code project_member_roles} da pessoa, com {@code source=MANUAL}, {@code is_leadership=true} e o papel do fundador de
 * {@code JiraProfieldsService#createManualProject} (Agile Master, ou People Lead se a pessoa já é). Os dois lados
 * (vínculo e {@code squad_id}) são gravados, o dual-write de sempre; o cache de vínculos é invalidado pelo listener JPA
 * ({@link MembershipCacheEvictionListener}).
 *
 * <p>Em {@code new}, além da condição legada, só concede quando a squad não tem NENHUM vínculo nem projeto cadastrado e
 * a pessoa não tem vínculo em lugar nenhum (quem já tem time cria outra equipe pelo fluxo de projetos). Nunca concede a
 * quem tenta entrar numa squad que já tem pessoas ou liderança. Sem PII em log: só o id da conta e a chave da squad.
 */
@Service
@Slf4j
public class SquadBootstrapService {

    private final UserRepository userRepository;
    private final SquadService squadService;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final JiraProfieldsService jiraProfieldsService;
    private final MembershipService membershipService;
    private final AuthzShadow authz;
    /** app.membership.bootstrap=on: em authz=new, o bootstrap cria o vínculo. Padrão off (sem concessão nenhuma em new). */
    private final boolean bootstrapOn;

    /** Sem os colaboradores do modo novo (testes e montagens manuais): só o comportamento legado. */
    public SquadBootstrapService(UserRepository userRepository, SquadService squadService) {
        this(userRepository, squadService, null, null, null, null, null, "off");
    }

    @Autowired
    public SquadBootstrapService(UserRepository userRepository, SquadService squadService,
                                 ProjectMemberRoleRepository projectMemberRoleRepository,
                                 ProjectConfigRepository projectConfigRepository,
                                 JiraProfieldsService jiraProfieldsService,
                                 MembershipService membershipService, AuthzShadow authz,
                                 @Value("${app.membership.bootstrap:off}") String bootstrapRaw) {
        this.userRepository = userRepository;
        this.squadService = squadService;
        this.projectMemberRoleRepository = projectMemberRoleRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.jiraProfieldsService = jiraProfieldsService;
        this.membershipService = membershipService;
        this.authz = authz;
        this.bootstrapOn = bootstrapRaw != null && "on".equalsIgnoreCase(bootstrapRaw.trim());
        if (bootstrapRaw != null && !bootstrapRaw.isBlank()
                && !"on".equalsIgnoreCase(bootstrapRaw.trim()) && !"off".equalsIgnoreCase(bootstrapRaw.trim())) {
            log.warn("app.membership.bootstrap inválido ({}): usando off", AuthzShadow.sanitize(bootstrapRaw));
        }
        log.info("Vínculo: app.membership.bootstrap={}", bootstrapOn ? "on" : "off");
    }

    /** O vínculo só é criado com authz={@code new} E app.membership.bootstrap=on (e os colaboradores existem). */
    boolean createsMembership() {
        return bootstrapOn && authz != null && authz.mode() == AuthzMode.NEW && projectMemberRoleRepository != null
                && projectConfigRepository != null && jiraProfieldsService != null && membershipService != null;
    }

    /**
     * Tenta conceder à pessoa a primeira entrada na squad. {@code true} = concedeu (a pessoa já pode seguir com a
     * chamada); {@code false} = não é um primeiro uso de squad vazia. Serializado: duas pessoas chegando juntas a uma
     * squad vazia não viram as duas "fundadoras" (em uma única instância do backend).
     */
    public synchronized boolean bootstrap(String squadId, String userId) {
        User caller = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (caller == null || squadId == null || squadId.isBlank()) {
            return false;
        }
        boolean hasNoSquad = isEmptySquadField(caller.getSquadId());
        if (!hasNoSquad) {
            return false;
        }
        boolean squadAlreadyExists = squadService.getSquad(squadId).isPresent() || !squadService.getMembers(squadId).isEmpty();
        if (squadAlreadyExists) {
            return false;
        }
        boolean createMembership = createsMembership();
        if (authz != null && authz.mode() == AuthzMode.NEW && !createMembership) {
            // authz=new com bootstrap desligado (padrão): ninguém ganha acesso por aqui; squad vazia é 403 para não-ADMIN.
            return false;
        }
        if (createMembership && !isFirstUseOfEmptySquad(caller, squadId.trim())) {
            return false;
        }
        boolean hasNoProject = isEmptySquadField(caller.getDefaultProjectId());

        if (createMembership) {
            createFounderMembership(caller, squadId.trim());
        }
        caller.setSquadId(squadId);
        if (hasNoProject) {
            caller.setDefaultProjectId(squadId);
        }
        userRepository.save(caller);
        log.info("authz_bootstrap user={} squad={}", AuthzShadow.sanitize(caller.getId()), AuthzShadow.sanitize(squadId));
        return true;
    }

    private static boolean isEmptySquadField(String value) {
        return value == null || value.isBlank() || "Sem Time".equalsIgnoreCase(value.trim());
    }

    /** Em {@code new}: a squad não tem vínculo nem projeto (sem caixa) e a pessoa não tem vínculo em lugar nenhum. */
    private boolean isFirstUseOfEmptySquad(User caller, String squadId) {
        String upper = squadId.toUpperCase(Locale.ROOT);
        if (!projectMemberRoleRepository.findByProjectIdsUpper(List.of(upper)).isEmpty()) {
            return false;
        }
        if (projectConfigRepository.existsById(squadId) || projectConfigRepository.existsById(upper)) {
            return false;
        }
        return membershipService.membershipsOf(caller).isEmpty();
    }

    private void createFounderMembership(User caller, String squadId) {
        if (!projectConfigRepository.existsById(squadId)) {
            projectConfigRepository.save(ProjectConfig.builder()
                    .id(squadId)
                    .name(squadId)
                    .status("EM ANDAMENTO")
                    .devTeamSize(1)
                    .build());
        }
        ProjectMemberRole founder = jiraProfieldsService.founderRow(squadId, caller, true);
        if (founder.getDisplayName() == null || founder.getDisplayName().isBlank()) {
            founder.setDisplayName(caller.getEmail() != null ? caller.getEmail() : caller.getId());
        }
        projectMemberRoleRepository.save(founder);
    }
}
