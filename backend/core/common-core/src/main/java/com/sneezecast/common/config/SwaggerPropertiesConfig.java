package com.sneezecast.common.config;

import com.sneezecast.common.properties.SwaggerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SwaggerProperties.class)
public class SwaggerPropertiesConfig {

}
