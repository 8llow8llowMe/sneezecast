package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import com.sneezecast.global.properties.DistrictImportProperties;
import com.sneezecast.global.properties.SgisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(JasyptPropertiesConfig.class)
@EnableConfigurationProperties({
    SgisProperties.class,
    DistrictImportProperties.class
})
public class BatchServicePropertiesConfig {

}
