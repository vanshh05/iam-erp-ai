package com.iamerp.ai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Issues and validates JWT tokens.
 * The token carries the username (subject) and the company_id as a claim.
 * Because the token is signed with a server-only secret, the company_id
 * inside it cannot be forged or tampered with by the client.
 */
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration.ms:86400000}") // default 24h
    private long expirationMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    // ── Generate a token for a user ──
    public String generateToken(String username, String companyId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("company_id", companyId);
        claims.put("role", role);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    // ── Extract values ──
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractCompanyId(String token) {
        return extractAllClaims(token).get("company_id", String.class);
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ── Validate ──
    public boolean isTokenValid(String token, String username) {
        try {
            final String extracted = extractUsername(token);
            return extracted.equals(username) && !isExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }
}