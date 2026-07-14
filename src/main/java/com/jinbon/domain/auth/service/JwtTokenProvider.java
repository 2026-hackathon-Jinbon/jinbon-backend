package com.jinbon.domain.auth.service;

import com.jinbon.global.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 토큰 생성/검증 컴포넌트.
 *
 * 토큰 종류:
 * - access: API 인증용 (30분)
 * - refresh: 토큰 갱신용 (7일)
 * - signup: 회원가입 DID 연결용 (30분)
 * - did_rebind: DID 재바인딩용 (10분)
 *
 * 모든 토큰에 type 클레임을 포함하여 용도별 검증이 가능하다.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final long DID_REBIND_EXPIRATION = 10 * 60 * 1000L;

    private final JwtProperties jwtProperties;
    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long memberId, String role) {
        return createToken(memberId, role, jwtProperties.getAccessExpiration(), "access");
    }

    public String createRefreshToken(Long memberId, String role) {
        return createToken(memberId, role, jwtProperties.getRefreshExpiration(), "refresh");
    }

    public String createSignupToken(Long memberId) {
        return createToken(memberId, "PENDING", 30 * 60 * 1000L, "signup");
    }

    public String createDidRebindToken(Long memberId) {
        return createToken(memberId, "ACTIVE", DID_REBIND_EXPIRATION, "did_rebind");
    }

    public long getDidRebindExpiration() {
        return DID_REBIND_EXPIRATION;
    }

    private String createToken(Long memberId, String role, long expiration, String type) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("role", role)
                .claim("type", type)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(validity)
                .signWith(key)
                .compact();
    }

    public long getRefreshExpiration() {
        return jwtProperties.getRefreshExpiration();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Authentication getAuthentication(String token) {
        Claims claims = getClaims(token);
        String role = claims.get("role", String.class);
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
    }

    public Long getMemberId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public String getTokenType(String token) {
        return getClaims(token).get("type", String.class);
    }

    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
