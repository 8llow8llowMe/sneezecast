package com.sneezecast.domainlayer.auth.application.port.out;

import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;

/**
 * 인가 코드로 소셜 제공자의 사용자 정보를 받는다(토큰 교환 → 사용자 정보). 원격 호출이라 <b>DB 트랜잭션 밖에서</b> 부른다. 구현은 connect / read
 * timeout 을 건다. 제공자 access token 은 이 호출 안에서만 쓰고 돌려주지 않는다.
 *
 * <p>실패는 {@code AuthException} 으로 올린다 — 제공자가 코드를 거부(4xx)하면 {@code OAUTH_LOGIN_FAILED}, 5xx · 연결 실패 · timeout · 해석할 수 없는
 * 응답이면 {@code OAUTH_PROVIDER_UNAVAILABLE}.
 */
public interface OAuthMemberQueryPort {

    OAuthMemberQueryResult fetchMember(String authorizationCode);
}
