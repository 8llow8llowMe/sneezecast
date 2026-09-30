package com.sneezecast.redis.config;

import com.sneezecast.redis.properties.RedisProperties;
import com.sneezecast.redis.properties.enums.RedisMode;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

public class RedisConfigurer {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {
        RedisMode mode = redisProperties.mode() != null ? redisProperties.mode() : RedisMode.STANDALONE;
        boolean hasPassword = redisProperties.password() != null && !redisProperties.password().isBlank();

        return switch (mode) {
            case SENTINEL -> createSentinelConnectionFactory(redisProperties, hasPassword);
            case STANDALONE -> createStandaloneConnectionFactory(redisProperties, hasPassword);
        };
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        // 주의: 기본 ObjectMapper 라 java.time 타입을 직렬화하지 못한다.
        // 객체 저장이 필요하면 StringRedisTemplate + 서비스 ObjectMapper 로 JSON 문자열을 직접 다룬다.
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        // hash 직렬화기를 지정하지 않으면 JDK 직렬화로 폴백한다 — opsForHash 를 쓰는 순간
        // redis-cli 로 읽을 수 없는 바이너리가 저장되므로 key/value 와 같은 정책으로 맞춘다.
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return redisTemplate;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    /**
     * Sentinel 접속 구성.
     *
     * <p>설정이 비었을 때 <b>기동 시점에 실패시킨다.</b> 예전에는 masterName 과 노드 목록을
     * 검사하지 않아, sentinel 모드로 띄우면 노드 목록이 null 인 채로 NPE 가 났다.
     * 스택트레이스만 보면 원인이 설정 누락이라는 것이 드러나지 않는다.
     */
    private LettuceConnectionFactory createSentinelConnectionFactory(RedisProperties redisProperties, boolean hasPassword) {
        String masterName = redisProperties.masterName();
        if (masterName == null || masterName.isBlank()) {
            throw new IllegalStateException(
                "infra.redis.mode=sentinel 인데 infra.redis.master-name 이 비어 있습니다. "
                    + "REDIS_MASTER_NAME 을 설정하세요.");
        }

        var nodes = redisProperties.resolvedSentinels();
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                "infra.redis.mode=sentinel 인데 Sentinel 노드가 없습니다. "
                    + "REDIS_SENTINEL_NODES 를 host:port,host:port 형식으로 설정하세요.");
        }

        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
            .master(masterName);

        nodes.forEach(node -> sentinelConfig.sentinel(node.host(), node.port()));

        if (hasPassword) {
            sentinelConfig.setPassword(RedisPassword.of(redisProperties.password()));
        }

        return new LettuceConnectionFactory(sentinelConfig, clientConfiguration(redisProperties));
    }

    private LettuceConnectionFactory createStandaloneConnectionFactory(RedisProperties redisProperties, boolean hasPassword) {
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(redisProperties.host(), redisProperties.port());

        if (hasPassword) {
            standaloneConfig.setPassword(RedisPassword.of(redisProperties.password()));
        }

        return new LettuceConnectionFactory(standaloneConfig, clientConfiguration(redisProperties));
    }

    /** 두 모드가 같은 명령 타임아웃을 쓴다. 지정하지 않으면 Lettuce 기본값 60초가 걸린다. */
    private LettuceClientConfiguration clientConfiguration(RedisProperties redisProperties) {
        return LettuceClientConfiguration.builder()
            .commandTimeout(redisProperties.resolvedCommandTimeout())
            .build();
    }
}
