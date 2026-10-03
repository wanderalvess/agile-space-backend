package com.agilespace.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS como Filter (não via WebMvcConfigurer) para que os headers sejam aplicados também
 * em respostas 401/403 escritas diretamente pelo JwtAuthenticationFilter, que roda antes
 * do DispatcherServlet e portanto não passaria pelo processamento CORS baseado em HandlerMapping.
 *
 * Origens vêm de ALLOWED_ORIGINS (lista separada por vírgula) em vez de "*": com
 * allowCredentials=true, aceitar qualquer origem equivale a confiar em qualquer site que
 * decida chamar essa API a partir do browser de um usuário logado.
 */
@Configuration
public class WebCorsConfig {

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
