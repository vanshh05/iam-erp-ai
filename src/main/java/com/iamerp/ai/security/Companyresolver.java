package com.iamerp.ai.security;

import com.iamerp.ai.model.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for "which company does this request operate on?"
 *
 * Security rule (do not weaken):
 *  - A normal user (role != ADMIN) is ALWAYS locked to the company_id baked into
 *    their token. Any company they try to pass in the request is ignored.
 *  - An ADMIN may operate on a company they select per-request. This override is
 *    honored ONLY because the role comes from the verified token, which the
 *    client cannot forge.
 */
@Component
public class CompanyResolver {

    /** The authenticated user from the verified JWT, or null. */
    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }

    public boolean isAdmin() {
        User u = currentUser();
        return u != null && "ADMIN".equalsIgnoreCase(u.getRole());
    }

    /**
     * Resolve the effective company for this request.
     *
     * @param requestedCompanyId company the client asked for (from request body/param).
     *                           Honored ONLY for admins; ignored for everyone else.
     * @return the company_id to use, or null if unauthenticated or admin selected nothing.
     */
    public String resolve(String requestedCompanyId) {
        User u = currentUser();
        if (u == null) return null;

        if ("ADMIN".equalsIgnoreCase(u.getRole())) {
            // Admin chooses the company per-request. If they haven't selected one, null.
            if (requestedCompanyId == null || requestedCompanyId.isBlank()) return null;
            return requestedCompanyId.trim();
        }

        // Non-admin: always the token's company, request value ignored entirely.
        return u.getCompanyId();
    }
}