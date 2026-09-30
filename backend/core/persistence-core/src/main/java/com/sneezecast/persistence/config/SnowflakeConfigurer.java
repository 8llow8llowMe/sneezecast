package com.sneezecast.persistence.config;

import com.sneezecast.persistence.properties.SnowflakeProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;

public class SnowflakeConfigurer {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties properties) {
        // 서버마다 workerId / dataCenterId를 다르게 설정해줘야함
        return new SnowflakeIdGenerator(properties.datacenterId(), properties.workerId());
    }
}
