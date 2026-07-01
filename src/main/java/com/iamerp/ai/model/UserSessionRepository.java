package com.iamerp.ai.model;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    // Used on every request to check the token is still an active session
    boolean existsByUsernameAndSessionToken(String username, String sessionToken);

    Optional<UserSession> findBySessionToken(String sessionToken);

    // All sessions for a user, oldest login first (for FIFO eviction)
    List<UserSession> findByUsernameOrderByCreatedAtAsc(String username);

    long countByUsername(String username);
}