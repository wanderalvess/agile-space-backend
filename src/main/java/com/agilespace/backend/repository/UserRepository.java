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

    /** Quantos admins ativos existem (guarda do último admin). */
    long countByRoleIgnoreCaseAndActiveTrue(String role);

    /** Contas ativas cuja squad da sessão (squadId/defaultProjectId) está entre os ids informados (MAIÚSCULOS). */
    @Query("SELECT u FROM User u WHERE u.active = true AND (UPPER(u.squadId) IN :ids OR UPPER(u.defaultProjectId) IN :ids)")
    List<User> findActiveBySquadIds(@Param("ids") Collection<String> upperSquadIds);

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
