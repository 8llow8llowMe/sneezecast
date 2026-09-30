package com.sneezecast.security.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtAuthProperties(
    String accessKey,
    Duration accessExpiration,
    String refreshKey,
    Duration refreshExpiration
) {

}
