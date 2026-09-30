package com.sneezecast.security.resourceserver.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.jwt.resource")
public record JwtResourceServerProperties(
    String accessKey
) {

}
