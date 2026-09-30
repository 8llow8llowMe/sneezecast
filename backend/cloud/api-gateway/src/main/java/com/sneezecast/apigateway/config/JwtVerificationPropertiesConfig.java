package com.sneezecast.apigateway.config;

import com.sneezecast.apigateway.jwt.properties.JwtVerificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtVerificationProperties.class)
public class JwtVerificationPropertiesConfig {

}
