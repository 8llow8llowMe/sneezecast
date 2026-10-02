package com.sneezecast.domainlayer.auth.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode {

    // 이메일 인증
    EMAIL_CODE_COOLDOWN("AUTH_001", "인증코드 요청이 너무 잦습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    // IP 기준 발송 상한 초과. 이메일 쿨다운(AUTH_001)과 별개의 남용 방어다.
    EMAIL_SEND_IP_LIMITED("AUTH_002", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    INVALID_EMAIL_CODE("AUTH_003", "인증코드가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    // 가입된 이메일에도 미끼 코드를 저장하므로 이 코드는 가입 여부와 무관하다 — 요청한 적이 없거나, 수명이 지났거나, 시도 초과로 지워진 경우다.
    EXPIRED_EMAIL_CODE("AUTH_004", "인증코드가 만료되었거나 요청 이력이 없습니다. 다시 요청해주세요.", HttpStatus.BAD_REQUEST),
    // 오입력 누적 — 브루트포스 방어로 코드를 무효화하고 재요청을 유도한다.
    EMAIL_CODE_ATTEMPTS_EXCEEDED("AUTH_005", "인증코드 시도 횟수를 초과했습니다. 인증코드를 다시 요청해주세요.", HttpStatus.BAD_REQUEST),
    // 인증 상태 저장소(Redis) 장애. 봉투 없는 500 대신 재시도 가능한 503 으로 알린다.
    EMAIL_VERIFICATION_UNAVAILABLE("AUTH_006", "일시적으로 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // 가입
    EMAIL_NOT_VERIFIED("AUTH_007", "이메일 인증이 완료되지 않았습니다. 인증 후 다시 시도해주세요.", HttpStatus.BAD_REQUEST),
    // web 경계의 @AssertTrue(AUTH_110~112)와 별개로 가입 로직이 한 번 더 지키는 불변식이다 — DTO 를 거치지 않는 경로가 생겨도
    // 동의하지 않은 회원의 동의 행이 만들어지지 않게 한다. 동의 누락과 나이 확인 누락은 화면이 다른 체크박스를 강조하도록 코드를 나눈다.
    CONSENT_REQUIRED("AUTH_008", "이용약관과 개인정보 수집·이용에 동의해야 가입할 수 있습니다.", HttpStatus.BAD_REQUEST),
    AGE_REQUIREMENT_NOT_MET("AUTH_009", "만 19세 이상만 가입할 수 있습니다.", HttpStatus.BAD_REQUEST),
    // 검증 API 의 IP 기준 상한 초과 — 여러 이메일에 걸친 코드 대입을 늦춘다. 발송 상한(AUTH_002)과 별개다.
    EMAIL_VERIFY_IP_LIMITED("AUTH_010", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),

    // 로그인
    // 미가입 이메일 · 비밀번호 없는(소셜) 계정 · 비밀번호 불일치를 가르지 않는다 — 계정 열거 방지.
    LOGIN_FAILED("AUTH_011", "이메일 또는 비밀번호가 일치하지 않습니다.", HttpStatus.UNAUTHORIZED),
    // 이메일당 연속 실패 잠금. 계정 존재 여부와 무관하게 같은 임계값에서 걸린다. 잠금 시간은 설정값이라 메시지에 못 박지 않는다.
    LOGIN_ATTEMPT_LOCKED("AUTH_012", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    // IP 기준 실패 상한 — 여러 이메일에 걸친 비밀번호 대입을 늦춘다. 이메일 잠금(AUTH_012)과 별개다.
    LOGIN_IP_LIMITED("AUTH_013", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),

    // 토큰 · 세션
    // 쿠키가 없거나, refresh 토큰이 만료됐거나, 세션이 만료 · 폐기됐다 — 다시 로그인한다.
    REFRESH_TOKEN_EXPIRED("AUTH_014", "로그인이 만료되었습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    // 서명 · 형식이 틀렸거나, 이미 회전된 토큰을 다시 썼다(탈취 의심 — 그 세션을 폐기한다). 다시 로그인한다.
    REFRESH_TOKEN_INVALID("AUTH_015", "로그인 정보가 유효하지 않습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    // 여러 탭이 같은 쿠키로 동시에 재발급한 경합에서 진 쪽. 세션은 그대로이고, 한 번 다시 시도하면 브라우저가 이긴 쪽의 새 쿠키를 보낸다.
    REFRESH_TOKEN_ROTATED("AUTH_016", "토큰이 방금 갱신되었습니다. 다시 시도해주세요.", HttpStatus.CONFLICT),
    // 세션 저장소(Redis) 장애. 봉투 없는 500 대신 재시도 가능한 503 으로 알린다.
    SESSION_STORE_UNAVAILABLE("AUTH_017", "일시적으로 로그인 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // 비밀번호 재설정
    // 재설정 토큰이 없거나 · 만료됐거나 · 이미 썼다. 토큰의 이메일에 정상 회원이 없어도 같은 코드다 — 가입 여부 · 계정 상태를 드러내지 않는다.
    // 화면은 인증코드 단계부터 다시 하게 한다.
    PASSWORD_RESET_EXPIRED("AUTH_018", "비밀번호 재설정 인증이 만료되었습니다. 인증코드를 다시 요청해주세요.", HttpStatus.BAD_REQUEST),
    // 재설정 요청의 IP 기준 시도 상한 — 토큰 대입 · 대량 재설정을 늦춘다.
    PASSWORD_RESET_IP_LIMITED("AUTH_019", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),

    // 카카오 로그인 — 화면은 모두 /login?error=kakao-fail 로 보낸다. 사유를 나누는 것은 로그 · 문의 대응용이다.
    // state 쿠키 없음 · 쿠키와 불일치 · 저장소에 없음(만료 · 이미 씀)을 가르지 않는다 — 어느 쪽이 틀렸는지 알려 줄 이유가 없다.
    OAUTH_STATE_INVALID("AUTH_020", "카카오 로그인 요청이 만료되었거나 올바르지 않습니다. 처음부터 다시 시도해주세요.", HttpStatus.BAD_REQUEST),
    // 카카오가 인가 코드를 거부했다(4xx — invalid_grant: 만료 · 이미 씀 · 다른 redirect_uri 등). 같은 코드로는 다시 시도할 수 없다.
    OAUTH_LOGIN_FAILED("AUTH_021", "카카오 로그인에 실패했습니다. 처음부터 다시 시도해주세요.", HttpStatus.BAD_REQUEST),
    // 카카오 5xx · 연결 실패 · timeout · 해석할 수 없는 응답. 봉투 없는 500 대신 재시도 가능한 503 으로 알린다.
    OAUTH_PROVIDER_UNAVAILABLE("AUTH_022", "일시적으로 카카오 로그인을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    // 카카오 계정에 이메일이 없거나 이메일 제공에 동의하지 않았다. 회원은 이메일로 식별하므로 가입하지 않는다.
    OAUTH_EMAIL_REQUIRED("AUTH_023", "카카오 계정의 이메일 제공에 동의해야 로그인할 수 있습니다.", HttpStatus.BAD_REQUEST),
    // 카카오가 이메일을 인증되지 않았거나(is_email_verified) 유효하지 않다고(is_email_valid) 알렸다. 남의 이메일로 가입 · 연결되는 길을 막는다.
    OAUTH_EMAIL_UNVERIFIED("AUTH_024", "카카오 계정의 이메일이 인증되지 않았습니다. 카카오에서 이메일을 인증한 뒤 다시 시도해주세요.", HttpStatus.BAD_REQUEST),
    // 가입표가 없거나 만료 · 이미 썼다. 화면은 카카오 로그인부터 다시 한다.
    OAUTH_SIGNUP_TICKET_EXPIRED("AUTH_025", "카카오 가입 시간이 지났습니다. 카카오 로그인부터 다시 시도해주세요.", HttpStatus.BAD_REQUEST),
    // 연결 확인표가 없거나 만료 · 이미 썼다. 화면은 카카오 로그인부터 다시 한다.
    OAUTH_LINK_TICKET_EXPIRED("AUTH_026", "카카오 로그인 연결 시간이 지났습니다. 카카오 로그인부터 다시 시도해주세요.", HttpStatus.BAD_REQUEST),
    // 확인표를 받은 뒤 계정 상태가 바뀌었다(탈퇴 · 정지 · 이미 연결됨). 상태를 자세히 드러내지 않는다.
    OAUTH_LINK_NOT_ALLOWED("AUTH_027", "카카오 로그인을 연결할 수 없는 계정입니다.", HttpStatus.CONFLICT),
    // 인가 주소(state) 발급의 IP 기준 상한 — 인증 없이 부르는 API 라 state 키로 Redis 를 채우지 못하게 한다. 창 길이는 설정값이라 메시지에 못 박지 않는다.
    OAUTH_AUTHORIZE_IP_LIMITED("AUTH_028", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),

    // 요청 검증 대역 — 필드별 코드(AUTH_101~)는 AuthValidationMessage 가 단일 기준점이며 여기서 중복 정의하지 않는다.
    INVALID_REQUEST("AUTH_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("AUTH_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("AUTH_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
