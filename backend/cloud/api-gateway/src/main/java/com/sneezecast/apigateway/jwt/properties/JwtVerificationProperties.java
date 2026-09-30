package com.sneezecast.apigateway.jwt.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtVerificationProperties(
    String accessKey,
    boolean blacklistFailOpen
) {

}
