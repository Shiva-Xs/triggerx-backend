package com.triggerx.auth;

import com.triggerx.config.PublicPaths;
import com.triggerx.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private static final AntPathMatcher ANT = new AntPathMatcher();

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            try {
                JwtService.VerifiedToken verified = jwtService.verify(token);
                // A token is only accepted if its "tv" claim still matches the
                // user's current token_version. logout-all bumps that column,
                // which instantly invalidates every previously issued token.
                userRepository.findById(verified.userId())
                        .filter(user -> user.getTokenVersion() == verified.tokenVersion())
                        .ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(user.getId(), null, List.of())));
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Invalid JWT from {}: {}", request.getRemoteAddr(), e.getMessage());
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return PublicPaths.PATTERNS.stream().anyMatch(p -> ANT.match(p, path));
    }
}
