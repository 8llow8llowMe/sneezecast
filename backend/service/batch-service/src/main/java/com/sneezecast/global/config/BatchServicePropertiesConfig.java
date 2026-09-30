package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(JasyptPropertiesConfig.class)
public class BatchServicePropertiesConfig {

}
