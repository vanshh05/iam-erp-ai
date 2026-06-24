package com.iamerp.ai.model;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    // Most recent code issued for this email
    Optional<PasswordReset> findTopByEmailOrderByIdDesc(String email);
    // Most recent code issued for this username
    Optional<PasswordReset> findTopByUsernameOrderByIdDesc(String username);
}