package com.platform.movieservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;

@Configuration
public class TmdbConfig {

    @Value("${tmdb.api.base-url:https://api.themoviedb.org/3}")
    private String baseUrl;

    @Value("${tmdb.api.bearer-token:}")
    private String bearerToken;

    @Value("${tmdb.api.api-key:}")
    private String apiKey;

    @Value("${tmdb.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${tmdb.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Bean
    public RestClient tmdbRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        String token = StringUtils.hasText(bearerToken) ? bearerToken.trim() : null;
        String key = StringUtils.hasText(apiKey) ? apiKey.trim() : null;

        // If bearer token is provided, or if apiKey is a JWT read token
        if (token != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        } else if (key != null && key.length() > 50) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key);
        } else if (key != null) {
            // Standard TMDB v3 32-character API Key appended as query parameter
            builder.requestInterceptor((request, body, execution) -> {
                URI originalUri = request.getURI();
                URI newUri = UriComponentsBuilder.fromUri(originalUri)
                        .queryParam("api_key", key)
                        .build(true)
                        .toUri();

                HttpRequest modifiedRequest = new HttpRequestWrapper(request) {
                    @Override
                    public URI getURI() {
                        return newUri;
                    }
                };
                return execution.execute(modifiedRequest, body);
            });
        }

        return builder.build();
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public boolean hasValidCredentials() {
        return StringUtils.hasText(bearerToken) || StringUtils.hasText(apiKey);
    }
}
