package com.familyguard.familyguard.security;

import com.familyguard.familyguard.service.AutenticacionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AutenticacionService autenticacionService;

    public BearerTokenAuthenticationFilter(AutenticacionService autenticacionService) {
        this.autenticacionService = autenticacionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extraerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            autenticacionService.autenticar(token).ifPresent(principal -> {
                var authority = new SimpleGrantedAuthority("ROLE_" + principal.rol().name());
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }

    public static String extraerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).strip();
        return token.isEmpty() ? null : token;
    }
}
