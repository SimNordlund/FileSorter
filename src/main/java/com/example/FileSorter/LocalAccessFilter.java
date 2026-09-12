package com.example.FileSorter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** Prevent unrelated websites from issuing local file-management commands. */
@Component
public class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private final String token = UUID.randomUUID().toString();

    public String token() { return token; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Language", "sv");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if (!HOSTS.contains(request.getServerName()) || !validOrigin(request)) {
            response.sendError(403, "Öppna File Sorter direkt på localhost.");
            return;
        }
        String path = request.getServletPath();
        if (path.startsWith("/api/") && !path.equals("/api/config")
                && !token.equals(request.getHeader("X-FileSorter-Token"))) {
            response.sendError(403, "Ladda om File Sorter för att ansluta igen.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean validOrigin(HttpServletRequest request) {
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return request.getScheme().equals(uri.getScheme()) && request.getServerName().equals(uri.getHost())
                    && request.getServerPort() == port;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
