package com.sneezecast.redis.properties.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RedisMode {

    STANDALONE("단일 Redis 인스턴스 (로컬/개발 테스트용)"),
    SENTINEL("Redis Sentinel 기반 고가용성(HA) 모드");

    private final String description;
}
