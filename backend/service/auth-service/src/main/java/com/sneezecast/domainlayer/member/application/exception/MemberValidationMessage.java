package com.sneezecast.domainlayer.member.application.exception;

import com.sneezecast.domainlayer.member.domain.policy.MemberInputPolicy;

/**
 * 회원 요청 검증 메시지 카탈로그 (MEMBER_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 코드와 메시지를 여기 모아 DTO 가 참조하게
 * 하면 오타 · 삭제를 컴파일러가 잡는다. 형식은 {@code "코드:사용자 메시지"} — {@code ValidationErrorSupport} 가 접두어를 분리한다.
 *
 * <p>닉네임 · 새 비밀번호의 <b>규칙</b>은 가입과 같다({@link MemberInputPolicy}). 코드만 이 컨텍스트 대역을 쓴다.
 */
public final class MemberValidationMessage {

    public static final String NICKNAME_REQUIRED = "MEMBER_101:닉네임은 필수입니다.";
    public static final String NICKNAME_LENGTH_INVALID = "MEMBER_102:닉네임은 2자 이상 10자 이하여야 합니다.";
    public static final String CURRENT_PASSWORD_REQUIRED = "MEMBER_103:현재 비밀번호는 필수입니다.";
    // 현재 비밀번호는 가입 규칙을 걸지 않는다 — 규칙이 바뀌어도 기존 비밀번호로 확인할 수 있어야 한다(로그인 AUTH_113 과 같은 이유).
    public static final String CURRENT_PASSWORD_LENGTH_INVALID = "MEMBER_104:현재 비밀번호는 100자 이하여야 합니다.";
    public static final String NEW_PASSWORD_REQUIRED = "MEMBER_105:새 비밀번호는 필수입니다.";
    // 길이는 @Size(MEMBER_106), 문자 구성은 @Pattern(MEMBER_107)이 맡는다.
    public static final String NEW_PASSWORD_LENGTH_INVALID = "MEMBER_106:새 비밀번호는 8자 이상 20자 이하여야 합니다.";
    public static final String NEW_PASSWORD_PATTERN_INVALID = "MEMBER_107:새 비밀번호는 공백 없이 영문자와 숫자를 각각 1자 이상 포함해야 합니다.";
    public static final String CONSENT_TYPE_REQUIRED = "MEMBER_108:동의 항목은 필수입니다.";
    public static final String DOCUMENT_VERSION_REQUIRED = "MEMBER_109:문서 버전은 필수입니다.";
    // 상한은 member_consent.document_version 컬럼 길이다. 저장값은 서버 설정이고 요청값은 현재 버전과 같은지(MEMBER_011)만 본다.
    public static final String DOCUMENT_VERSION_LENGTH_INVALID = "MEMBER_110:문서 버전은 20자 이하여야 합니다.";

    /** 문서 버전 상한 — member_consent.document_version VARCHAR(20) · legal.*-version 설정 상한과 같다. */
    public static final int DOCUMENT_VERSION_MAX_LENGTH = 20;

    /** 새 비밀번호 문자 구성 규칙 — 가입과 같은 정규식(회원 입력 정책)을 쓴다. */
    public static final String PASSWORD_REGEXP = MemberInputPolicy.PASSWORD_REGEXP;

    private MemberValidationMessage() {
    }
}
