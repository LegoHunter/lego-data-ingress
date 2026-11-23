package io.legohunter.ingress.config.security;

import io.legohunter.ingress.photoupload.PhotoUploadAuthenticationFilter;
import io.legohunter.ingress.photoupload.PhotoUploadTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    /**
     * The Lightroom photo upload (HOMELAB-134). Only /internal/photo-upload/** matches this chain, and it is evaluated
     * before the chain below. The presign endpoint needs a valid access token; the refresh and status endpoints take
     * their refresh token in the body or header, so they are open at this layer.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain photoUploadSecurityFilterChain(HttpSecurity http,
                                                              PhotoUploadTokenService tokenService) throws Exception {
        http
                .securityMatcher("/internal/photo-upload/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new PhotoUploadAuthenticationFilter(tokenService),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/photo-upload/presign").hasRole("PHOTO_UPLOAD")
                        .requestMatchers("/internal/photo-upload/batches/**").hasRole("PHOTO_UPLOAD")
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, ex) ->
                        response.sendError(HttpStatus.UNAUTHORIZED.value())));

        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // This is a REST API, not a browser app
                .csrf(AbstractHttpConfigurer::disable)

                // Disable all built-in auth mechanisms
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                // Allow everything
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
