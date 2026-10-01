package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import com.sneezecast.global.properties.AuthMailProperties;
import com.sneezecast.global.properties.EmailSendLimitProperties;
import com.sneezecast.global.properties.LegalDocumentProperties;
import com.sneezecast.persistence.config.SnowflakePropertiesConfig;
import com.sneezecast.redis.config.RedisPropertiesConfig;
import com.sneezecast.security.auth.config.JwtAuthPropertiesConfig;
import com.sneezecast.storage.config.StoragePropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    JasyptPropertiesConfig.class,
    JwtAuthPropertiesConfig.class,
    RedisPropertiesConfig.class,
    StoragePropertiesConfig.class,
    SnowflakePropertiesConfig.class
})
@EnableConfigurationProperties({AuthMailProperties.class, EmailSendLimitProperties.class, LegalDocumentProperties.class})
public class AuthServicePropertiesConfig {

}
