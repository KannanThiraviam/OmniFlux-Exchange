package com.omniflux.exchange.web;

import com.omniflux.exchange.job.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;

@Configuration
public class WebConfiguration {
    @Bean
    public SystemController.JobMetrics jobMetrics(JobRepository jobs) {
        return new RepositoryJobMetrics(jobs);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    // No Content-Security-Policy constant exists in Spring's HttpHeaders; the
    // header spelling here is intentional and case-insensitive per RFC 9110.
    @SuppressWarnings("UnknownHttpHeader")
    public WebFilter contentSecurityPolicyFilter() {
        return (exchange, chain) -> {
            exchange.getResponse().getHeaders().set("Content-Security-Policy",
                    "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
                            + "object-src 'none'; form-action 'self'");
            return chain.filter(exchange);
        };
    }
}
