package com.sneezecast.global.config;

import com.sneezecast.common.config.JasyptPropertiesConfig;
import com.sneezecast.redis.config.RedisPropertiesConfig;
import com.sneezecast.security.auth.config.JwtAuthPropertiesConfig;
import com.sneezecast.storage.config.StoragePropertiesConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    JasyptPropertiesConfig.class,
    JwtAuthPropertiesConfig.class,
    RedisPropertiesConfig.class,
    StoragePropertiesConfig.class
})
public class AuthServicePropertiesConfig {

}
