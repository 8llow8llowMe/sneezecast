package com.sneezecast.apigateway.config;

import com.sneezecast.apigateway.filter.properties.TrustedProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class TrustedProxyPropertiesConfig {

}
