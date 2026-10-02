package com.sneezecast.domainlayer.auth.adapter.out.persistence;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** 여러 저장소 어댑터가 함께 쓰는 Lua 스크립트. */
final class RedisScripts {

    /**
     * 일회용 값을 꺼내고 지운다 — GET 과 DEL 을 한 스크립트로 묶는다. 나눠 보내면 같은 값으로 동시에 들어온 두 요청이 모두 GET 에 성공한다. 키가 없으면
     * nil(→ null)이다. 비밀번호 재설정 토큰 · 카카오 로그인 state · 가입표 · 연결 확인표가 쓴다.
     */
    static final RedisScript<String> GET_AND_DELETE = new DefaultRedisScript<>("""
        local value = redis.call('GET', KEYS[1])
        if value then
          redis.call('DEL', KEYS[1])
        end
        return value
        """, String.class);

    private RedisScripts() {
    }
}
