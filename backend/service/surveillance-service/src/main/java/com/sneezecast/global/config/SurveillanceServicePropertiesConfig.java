package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import com.sneezecast.domainlayer.advisory.application.service.ReviewCandidateProperties;
import com.sneezecast.domainlayer.aggregate.application.service.AggregateJobProperties;
import com.sneezecast.domainlayer.aggregate.application.service.AggregateProperties;
import com.sneezecast.domainlayer.official.application.service.OfficialNotifiableProperties;
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
@EnableConfigurationProperties({ReporterKeyProperties.class, AggregateProperties.class, AggregateJobProperties.class, OfficialNotifiableProperties.class,
    ReviewCandidateProperties.class})
public class SurveillanceServicePropertiesConfig {

}
