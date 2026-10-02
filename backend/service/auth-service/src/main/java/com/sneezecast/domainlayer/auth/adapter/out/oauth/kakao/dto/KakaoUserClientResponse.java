package com.sneezecast.domainlayer.auth.adapter.out.oauth.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 사용자 정보 응답({@code GET /v2/user/me}) 중 읽는 부분 — 이메일 · 이메일 인증 · 유효 여부 · 닉네임. 카카오 회원 ID · 프로필 이미지는 읽지 않는다
 * (최소 수집). 사용자가 동의하지 않은 항목은 응답에서 빠지므로 모두 null 일 수 있다. 이 타입은 어댑터 밖으로 나가지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserClientResponse(
    @JsonProperty("kakao_account")
    KakaoAccount kakaoAccount
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoAccount(
        @JsonProperty("email")
        String email,

        @JsonProperty("is_email_verified")
        Boolean emailVerified,

        @JsonProperty("is_email_valid")
        Boolean emailValid,

        @JsonProperty("profile")
        KakaoProfile profile
    ) {

        /** 이메일을 로그 · 예외 메시지에 흘리지 않는다. */
        @Override
        public String toString() {
            return "KakaoAccount[email=****, emailVerified=" + emailVerified + ", emailValid=" + emailValid + ", profile=****]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoProfile(
        @JsonProperty("nickname")
        String nickname
    ) {

        @Override
        public String toString() {
            return "KakaoProfile[nickname=****]";
        }
    }
}
