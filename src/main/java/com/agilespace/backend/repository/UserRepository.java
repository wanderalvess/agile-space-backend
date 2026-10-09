package com.agilespace.backend.repository;

import com.agilespace.backend.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
