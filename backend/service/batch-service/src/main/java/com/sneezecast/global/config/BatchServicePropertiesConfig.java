package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import com.sneezecast.global.properties.BatchScheduleProperties;
import com.sneezecast.global.properties.DistrictImportProperties;
import com.sneezecast.global.properties.KdcaProperties;
import com.sneezecast.global.properties.NotifiableImportProperties;
import com.sneezecast.global.properties.SentinelImportProperties;
import com.sneezecast.global.properties.SentinelPortalProperties;
import com.sneezecast.global.properties.SgisProperties;
import com.sneezecast.persistence.config.SnowflakePropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    JasyptPropertiesConfig.class,
    SnowflakePropertiesConfig.class
})
@EnableConfigurationProperties({
    SgisProperties.class,
    DistrictImportProperties.class,
    KdcaProperties.class,
    NotifiableImportProperties.class,
    SentinelPortalProperties.class,
    SentinelImportProperties.class,
    BatchScheduleProperties.class
})
public class BatchServicePropertiesConfig {

}
