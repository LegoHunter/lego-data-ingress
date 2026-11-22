package io.legohunter.ingress.photoupload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer <access token>" and, when the token is valid, marks the request as a photo-upload
 * client. It never refuses a request itself: the security chain decides (HOMELAB-134). Not a Spring bean, so it
 * is not registered for every request in the servlet container.
 */
public final class PhotoUploadAuthenticationFilter extends OncePerRequestFilter {
    static final String ROLE = "ROLE_PHOTO_UPLOAD";
    private static final String PREFIX = "Bearer ";

    private final PhotoUploadTokenService tokenService;

    public PhotoUploadAuthenticationFilter(PhotoUploadTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            tokenService.authenticateAccess(header.substring(PREFIX.length()).trim()).ifPresent(token ->
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            token.getTokenName(), null, List.of(new SimpleGrantedAuthority(ROLE)))));
        }
        chain.doFilter(request, response);
    }
}
