package com.iamerp.ai.security;

import com.iamerp.ai.model.User;
import com.iamerp.ai.model.UserRepository;
import com.iamerp.ai.model.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Runs on every request. If a valid "Authorization: Bearer <token>" header is
 * present, it authenticates the request and attaches the user (including the
 * verified company_id) to the security context.
 *
 * The company_id used by the rest of the app comes from this verified token,
 * NOT from the request body — so it cannot be forged.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository,
                         UserSessionRepository sessionRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7);

        try {
            final String username = jwtService.extractUsername(token);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                Optional<User> userOpt = userRepository.findByUsername(username);

                // Session check: the session token in the JWT must still exist in the sessions
                // table for this user. If it was evicted (single-device overwrite for owners, or
                // FIFO eviction past 5 devices for admins), it's gone → treated as logged out.
                boolean sessionMatches = false;
                if (userOpt.isPresent()) {
                    String tokenSession = jwtService.extractSessionToken(token);
                    sessionMatches = tokenSession != null
                        && sessionRepository.existsByUsernameAndSessionToken(username, tokenSession);
                }

                if (userOpt.isPresent()
                        && Boolean.TRUE.equals(userOpt.get().getIsActive())
                        && sessionMatches
                        && jwtService.isTokenValid(token, username)) {

                    User user = userOpt.get();
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" +
                            (user.getRole() == null ? "USER" : user.getRole().toUpperCase())));

                    // Principal = the User object; company_id is read from here later
                    var authToken = new UsernamePasswordAuthenticationToken(user, null, authorities);
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Invalid/expired token → leave unauthenticated; SecurityConfig will reject protected routes
            logger.warn("JWT validation failed: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}