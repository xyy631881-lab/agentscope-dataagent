/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.security;

import io.agentscope.dataagent.security.application.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.GenericFilterBean;

/**
 * agentscope-dataagent web 应用程序的 Spring MVC 安全配置。
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} — 公开（不需要令牌）
 *   <li>{@code /api/**} — 需要已认证用户
 *   <li>{@code /**} — 公开（提供 React SPA 静态文件）
 * </ul>
 *
 * <p>JWT 验证由 {@link JwtAuthFilter} 执行，该过滤器在
 * {@link UsernamePasswordAuthenticationFilter} 之前注册。
 * CORS 配置允许 Vite 开发服务器（{@code http://localhost:5173}）以及同源请求。
 *
 * <p>从 WebFlux 迁移至 Spring MVC：{@code ServerHttpSecurity} → {@code HttpSecurity}，
 * {@code WebFilter} → {@code OncePerRequestFilter}，
 * {@code ReactiveSecurityContextHolder} → {@code SecurityContextHolder}。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtService jwtService) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.POST, "/api/auth/login")
                                        .permitAll()
                                        .requestMatchers("/actuator/health", "/actuator/info")
                                        .permitAll()
                                        .requestMatchers("/api/webhook/**")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new JwtAuthFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * 从每个请求中提取并验证 JWT Bearer 令牌的 Servlet Filter。
     *
     * <p>从 WebFlux {@code WebFilter} 迁移为 Spring MVC
     * {@code OncePerRequestFilter}。区别：WebFilter 返回 {@code Mono<Void>}，
     * 而 OncePerRequestFilter 是同步的——直接调用 {@code chain.doFilter()} 即可。
     * 认证信息写入 {@link SecurityContextHolder}（线程局部变量），
 * 而非 WebFlux 的 {@code ReactiveSecurityContextHolder}（React 上下文）。
     */
    static class JwtAuthFilter extends GenericFilterBean {

        private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

        private final JwtService jwtService;

        JwtAuthFilter(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        /**
         * 继承 {@link GenericFilterBean}（普通 {@code Filter}）而非 Spring Framework 的
         * {@code OncePerRequestFilter}——后者在异步分发（async dispatch）时默认跳过。聊天 SSE
         * 端点（{@code SseEmitter}）是异步请求，容器在异步分发时会重跑整个 Security 过滤器链；
         * 若 JWT 过滤器被跳过，{@code SecurityContext} 为空 → 匿名 → {@code AuthorizationFilter}
         * 抛 {@code AccessDeniedException}。{@link GenericFilterBean} 在每次分发都会执行，
         * 异步分发时重新解析 JWT 并恢复认证。JWT 解析幂等，重复执行安全。
         */
        @Override
        public void doFilter(
                ServletRequest request, ServletResponse response, FilterChain filterChain)
                throws IOException, ServletException {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String header = httpRequest.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    Claims claims = jwtService.parse(token);
                    String userId = jwtService.extractUserId(claims);
                    List<String> roles = jwtService.extractRoles(claims);
                    List<SimpleGrantedAuthority> authorities =
                            roles.stream()
                                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                                    .toList();
                    SecurityContextHolder.getContext()
                            .setAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                            userId, null, authorities));
                } catch (JwtException e) {
                    log.debug("JWT 验证失败: {}", e.getMessage());
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
