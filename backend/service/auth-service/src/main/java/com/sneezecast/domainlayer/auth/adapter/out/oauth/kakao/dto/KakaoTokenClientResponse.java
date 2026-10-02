package com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 토큰 교환 응답({@code POST /oauth/token}). access token 만 쓴다 — refresh token · id_token 은 읽지 않고, access token 도 사용자 정보 한 번
 * 부르고 버린다. 이 타입은 어댑터 밖으로 나가지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoTokenClientResponse(
    @JsonProperty("access_token")
    String accessToken
) {

    /** 토큰 원문은 로그 · 예외 메시지에 흘리지 않는다. */
    @Override
    public String toString() {
        return "KakaoTokenClientResponse[accessToken=****]";
    }
}
