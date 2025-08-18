package mas.sheets.sheetsdatacleaner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleTokenAuthenticationFilter extends OncePerRequestFilter {

    private final GoogleTokenValidator tokenValidator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            log.debug("Validating Google OAuth token: {}...", token.substring(0, Math.min(20, token.length())));

            if (tokenValidator.validateToken(token)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken("google-user", null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Google OAuth token validation successful");
            } else {
                log.warn("Google OAuth token validation failed");
            }
        }

        filterChain.doFilter(request, response);
    }
}
