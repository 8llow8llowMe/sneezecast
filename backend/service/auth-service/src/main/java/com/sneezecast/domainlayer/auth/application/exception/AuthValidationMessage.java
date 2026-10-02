package com.sneezecast.domainlayer.auth.application.exception;

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

    /**
     * 비밀번호 문자 구성 규칙 — 시안(Signup-account "8자 이상 · 영문과 숫자 포함")대로 영문자 · 숫자 필수, 특수문자는 써도 되지만 요구하지 않는다.
     * 길이는 @Size 가 맡으므로 여기서는 구성만 본다 (같은 의미를 두 제약으로 중복 검사하지 않는다).
     */
    public static final String PASSWORD_REGEXP = "^(?=.*[A-Za-z])(?=.*\\d)\\S+$";

    /** 세션 아이디는 로그인 때 만든 UUID 다(소문자 hex). 그 밖의 값을 Redis 키에 넣지 않는다. */
    public static final String SESSION_ID_REGEXP = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private AuthValidationMessage() {
    }
}
