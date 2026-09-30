package com.sneezecast.common.config;

import com.sneezecast.common.properties.JasyptProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JasyptProperties.class)
public class JasyptPropertiesConfig {

}
