package com.sneezecast.persistence.config;

import com.sneezecast.persistence.properties.SnowflakeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakePropertiesConfig {

}

