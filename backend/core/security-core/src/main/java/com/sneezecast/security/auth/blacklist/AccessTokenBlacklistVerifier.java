package com.sneezecast.security.auth.blacklist;

/**
 * Access Token(jti)의 블랙리스트 등록 여부를 조회하는 계약.
 *
 * auth-service 로 직결되는 요청은 API Gateway 의 블랙리스트 검증을 받지 못한다. 이 인터페이스 구현 빈이
 * 존재하면 JwtAuthFilter 가 토큰 파싱 후 블랙리스트를 함께 검증한다(구현 빈이 없으면 기존 동작 유지).
 */
public interface AccessTokenBlacklistVerifier {

    boolean isRevoked(String tokenId);
}
