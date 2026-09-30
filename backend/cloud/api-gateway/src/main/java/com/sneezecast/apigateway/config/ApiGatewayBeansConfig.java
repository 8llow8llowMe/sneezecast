package com.sneezecast.apigateway.config;

import com.sneezecast.common.config.JasyptConfigurer;
import com.sneezecast.redis.config.RedisConfigurer;
import com.sneezecast.redis.config.RedisPropertiesConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    JasyptConfigurer.class,
    RedisPropertiesConfig.class,
    RedisConfigurer.class
})
public class ApiGatewayBeansConfig {

}
