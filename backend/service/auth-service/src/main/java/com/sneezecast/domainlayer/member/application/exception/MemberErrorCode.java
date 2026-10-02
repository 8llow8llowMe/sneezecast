package com.sneezecast.domainlayer.member.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원 도메인 오류. 필드별 검증 코드(MEMBER_101~)는 {@link MemberValidationMessage} 가 단일 기준점이며 여기서 중복 정의하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode {

    // 계정 상태(탈퇴 · 정지)를 드러내지 않도록 상태와 무관하게 같은 응답이다. 이메일 원문은 메시지에 싣지 않는다.
    EXIST_MEMBER_EMAIL("MEMBER_001", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT),
    // 로그인 · 토큰 재발급에서 쓴다. 로그인은 비밀번호가 맞을 때만 이 코드를 낸다 — 틀린 비밀번호로 계정 상태를 떠볼 수 없게 한다.
    WITHDRAWN_MEMBER("MEMBER_002", "탈퇴한 회원입니다.", HttpStatus.FORBIDDEN),
    SUSPENDED_MEMBER("MEMBER_003", "이용이 정지된 회원입니다.", HttpStatus.FORBIDDEN),

    // 내 정보 · 비밀번호
    // 토큰은 유효한데 회원 행이 없다(파기됨). 화면은 로그아웃 상태로 바꾼다.
    MEMBER_NOT_FOUND("MEMBER_004", "회원 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CURRENT_PASSWORD_MISMATCH("MEMBER_005", "현재 비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    // 현재 비밀번호 확인 실패가 쌓였다(회원 단위, 로그인 잠금과 같은 횟수 · 시간). 잠금 시간은 설정값이라 메시지에 못 박지 않는다.
    PASSWORD_CHANGE_LOCKED("MEMBER_006", "비밀번호 확인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    // 비밀번호가 없는(카카오로만 로그인하는) 계정의 변경 요청. 카카오 회원은 비밀번호가 필요 없어 설정 API 를 두지 않는다(#61).
    PASSWORD_NOT_SET("MEMBER_007", "카카오로 로그인하는 계정은 비밀번호가 없습니다.", HttpStatus.CONFLICT),
    // MEMBER_008 은 #61 에서 비밀번호 최초 설정(POST /members/me/password/setup)을 없애며 비운 번호다. 다른 의미로 다시 쓰지 않는다.
    // 다른 기기 세션을 끊지 못했다(세션 저장소 장애). 비밀번호는 바꾸지 않았으니 다시 시도하면 된다.
    SESSION_REVOKE_UNAVAILABLE("MEMBER_009", "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // 요청 검증 대역 — 필드별 코드(MEMBER_101~)는 MemberValidationMessage 에 있다.
    INVALID_REQUEST("MEMBER_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("MEMBER_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("MEMBER_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
