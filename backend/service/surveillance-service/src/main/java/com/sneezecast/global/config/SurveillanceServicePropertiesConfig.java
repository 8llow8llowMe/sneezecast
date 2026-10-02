package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import com.sneezecast.domainlayer.report.application.service.ReporterKeyProperties;
import com.sneezecast.persistence.config.SnowflakePropertiesConfig;
import com.sneezecast.security.resourceserver.config.JwtResourceServerPropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    JasyptPropertiesConfig.class,
    JwtResourceServerPropertiesConfig.class,
    SnowflakePropertiesConfig.class
})
@EnableConfigurationProperties(ReporterKeyProperties.class)
public class SurveillanceServicePropertiesConfig {

}
