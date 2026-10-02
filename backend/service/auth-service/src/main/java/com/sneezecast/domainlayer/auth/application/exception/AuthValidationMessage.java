package com.sneezecast.domainlayer.auth.application.exception;

import com.sneezecast.domainlayer.member.domain.policy.MemberInputPolicy;

/**
 * 인증 · 가입 요청 검증 메시지 카탈로그 (AUTH_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 코드와 메시지를 여기 모아 DTO 가 참조하게
 * 하면 오타 · 삭제를 컴파일러가 잡는다. 형식은 {@code "코드:사용자 메시지"} — {@code ValidationErrorSupport} 가 접두어를 분리한다.
 */
public final class AuthValidationMessage {

    public static final String EMAIL_REQUIRED = "AUTH_101:이메일은 필수입니다.";
    // member.email 컬럼 길이(100)와 같다. 넘기면 DB 오류(500)가 아니라 여기서 막는다.
    public static final String EMAIL_LENGTH_INVALID = "AUTH_102:이메일은 100자 이하여야 합니다.";
    public static final String EMAIL_FORMAT_INVALID = "AUTH_103:이메일 형식이 올바르지 않습니다.";
    public static final String EMAIL_CODE_REQUIRED = "AUTH_104:인증코드는 필수입니다.";
    public static final String PASSWORD_REQUIRED = "AUTH_105:비밀번호는 필수입니다.";
    // 길이는 @Size(AUTH_106), 문자 구성은 @Pattern(AUTH_107)이 맡는다. 두 메시지가 같은 내용을 겹쳐 말하지 않게 역할을 나눈다.
    public static final String PASSWORD_LENGTH_INVALID = "AUTH_106:비밀번호는 8자 이상 20자 이하여야 합니다.";
    public static final String PASSWORD_PATTERN_INVALID = "AUTH_107:비밀번호는 공백 없이 영문자와 숫자를 각각 1자 이상 포함해야 합니다.";
    public static final String NICKNAME_REQUIRED = "AUTH_108:닉네임은 필수입니다.";
    public static final String NICKNAME_LENGTH_INVALID = "AUTH_109:닉네임은 2자 이상 10자 이하여야 합니다.";
    public static final String TERMS_AGREEMENT_REQUIRED = "AUTH_110:이용약관에 동의해야 가입할 수 있습니다.";
    public static final String PRIVACY_AGREEMENT_REQUIRED = "AUTH_111:개인정보 수집·이용에 동의해야 가입할 수 있습니다.";
    public static final String AGE_OVER_19_REQUIRED = "AUTH_112:만 19세 이상만 가입할 수 있습니다.";
    // 로그인은 가입 규칙(AUTH_106 · 107)을 걸지 않는다 — 규칙이 바뀌어도 기존 비밀번호로 들어올 수 있어야 한다. 상한은 BCrypt 비용을 무의미하게
    // 큰 입력에 쓰지 않으려는 것이다.
    public static final String LOGIN_PASSWORD_LENGTH_INVALID = "AUTH_113:비밀번호는 100자 이하여야 합니다.";
    public static final String SESSION_ID_FORMAT_INVALID = "AUTH_114:세션 아이디 형식이 올바르지 않습니다.";
    public static final String RESET_TOKEN_REQUIRED = "AUTH_115:재설정 토큰은 필수입니다.";
    // 발급 토큰은 43자(32바이트 base64url)다. 상한은 무의미하게 큰 값을 해시 · 저장소 조회에 쓰지 않으려는 것이다.
    public static final String RESET_TOKEN_LENGTH_INVALID = "AUTH_116:재설정 토큰은 100자 이하여야 합니다.";
    public static final String OAUTH_CODE_REQUIRED = "AUTH_117:인가 코드는 필수입니다.";
    // 카카오 인가 코드는 수십~백여 자다. 상한은 무의미하게 큰 값을 카카오로 보내지 않으려는 것이다.
    public static final String OAUTH_CODE_LENGTH_INVALID = "AUTH_118:인가 코드는 512자 이하여야 합니다.";
    public static final String OAUTH_STATE_REQUIRED = "AUTH_119:state 는 필수입니다.";
    // 발급 state 는 43자(32바이트 base64url)다. 상한은 무의미하게 큰 값을 저장소 조회에 쓰지 않으려는 것이다.
    public static final String OAUTH_STATE_LENGTH_INVALID = "AUTH_120:state 는 100자 이하여야 합니다.";

    /**
     * 비밀번호 문자 구성 규칙 — 시안(Signup-account "8자 이상 · 영문과 숫자 포함")대로 영문자 · 숫자 필수, 특수문자는 써도 되지만 요구하지 않는다.
     * 규칙의 정본은 회원 입력 정책({@link MemberInputPolicy})이다 — 가입 · 재설정(auth)과 비밀번호 변경(member)이 같은 규칙을 쓴다.
     */
    public static final String PASSWORD_REGEXP = MemberInputPolicy.PASSWORD_REGEXP;

    /** 세션 아이디는 로그인 때 만든 UUID 다(소문자 hex). 그 밖의 값을 Redis 키에 넣지 않는다. */
    public static final String SESSION_ID_REGEXP = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private AuthValidationMessage() {
    }
}
