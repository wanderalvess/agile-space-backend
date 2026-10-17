package com.agilespace.backend.repository;

import com.agilespace.backend.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    /** Login por usuário: prefixo "usuario@" do e-mail (o Spring escapa % e _ no LIKE). */
    List<User> findByEmailStartingWithIgnoreCase(String emailPrefix);
    Optional<User> findByJiraAccountId(String jiraAccountId);

    /** Contas com o nível de conta informado (sem caixa). Usado pela ferramenta de rebaixamento de LEAD (Etapa 4). */
    List<User> findByRoleIgnoreCase(String role);

    /** Quantos admins ativos existem (guarda do último admin). */
    long countByRoleIgnoreCaseAndActiveTrue(String role);

    /**
     * Contas ativas cuja squad ativa preferida (default_project_id) está entre os ids informados (MAIÚSCULOS). Só o
     * caminho LEGADO de SquadAudienceService usa: users.squad_id foi aposentada (Etapa 5 do vínculo).
     */
    @Query("SELECT u FROM User u WHERE u.active = true AND UPPER(u.defaultProjectId) IN :ids")
    List<User> findActiveByDefaultProjectIds(@Param("ids") Collection<String> upperSquadIds);

    @Query("SELECT u FROM User u WHERE u.active = true AND LOWER(u.email) IN :emails")
    List<User> findActiveByEmails(@Param("emails") Collection<String> lowerEmails);

    @Query("SELECT u FROM User u WHERE u.active = true AND u.jiraAccountId IN :ids")
    List<User> findActiveByJiraAccountIds(@Param("ids") Collection<String> jiraAccountIds);

    @Query("SELECT u FROM User u WHERE u.active = true AND u.id IN :ids")
    List<User> findActiveByIds(@Param("ids") Collection<String> ids);

    @Query("SELECT u.id FROM User u WHERE u.active = true")
    List<String> findActiveIds();

    @Query("SELECT u.id FROM User u WHERE u.active = true AND UPPER(u.role) IN :roles")
    List<String> findActiveIdsByRoles(@Param("roles") Collection<String> upperRoles);
}
