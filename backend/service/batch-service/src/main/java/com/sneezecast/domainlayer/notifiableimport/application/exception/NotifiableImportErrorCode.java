package com.sneezecast.domainlayer.notifiableimport.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 질병관리청 전수신고 감염병 발생현황 적재 에러코드.
 *
 * <p>batch-service 에는 웹 API 가 없어 예외 핸들러를 두지 않는다. 예외는 Step 을 FAILED 로 끝내거나 요청 하나의 적재 이력
 * ({@code official_source_snapshot.error_code})에 남는다. {@code httpStatus} 는 컨벤션의 3필드 형식을 맞추려는 것이고 응답으로 나가지 않는다.
 *
 * <p><b>어느 코드가 실행 전체를 멈추는지는 여기서 정하지 않는다</b> — 잡을 조립하는 Facade 가 정한다. 각 코드의 javadoc 에는 뜻만 적는다.
 *
 * <p><b>메시지 자리 표시자에 인증키를 넣지 않는다.</b> 인증키가 요청 URL 의 쿼리에 실리므로 URL 이나 하위 예외 메시지도 넣지 않는다.
 * 응답 본문도 넣지 않는다 — 원천이 준 짧은 코드 · 메시지 필드만 넣는다.
 */
@Getter
@RequiredArgsConstructor
public enum NotifiableImportErrorCode {

    // 원천 (공공데이터포털 질병관리청 API) — 001 ~ 009

    /** 인증키 설정이 없다. 키가 없으면 어떤 요청도 성공하지 않는다. HTTP 호출 전에 판정한다. */
    KDCA_CREDENTIALS_MISSING("NOTIFIABLE_IMPORT_001", "질병관리청 API 인증키가 설정되지 않았습니다. (kdca.service-key / KDCA_API_SERVICE_KEY)",
        HttpStatus.INTERNAL_SERVER_ERROR),
    /** 인증키에 {@code %} 가 있다 — 공공데이터포털의 Encoding 값을 넣은 것으로 본다. 그대로 보내면 이중 인코딩되어 인증이 깨진다. HTTP 호출 전에 판정한다. */
    KDCA_SERVICE_KEY_LOOKS_ENCODED("NOTIFIABLE_IMPORT_002",
        "질병관리청 API 인증키에 '%%' 가 있습니다. 공공데이터포털의 Decoding 값을 넣으세요. (kdca.service-key / KDCA_API_SERVICE_KEY)",
        HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * 공공데이터포털 게이트웨이가 XML 봉투로 거절했다 (인증키 미등록 · 오류, 트래픽 초과 등). JSON 을 요청해도 XML 이 온다 (data-api-analysis §2-2).
     * 실행 중단 판단(#85)은 003 을 키 · 트래픽 문제로 본다 — 그래서 봉투 표식({@code OpenAPI_ServiceResponse} · {@code cmmMsgHeader})이 있을
     * 때만 003 이다. 표식 없는 마크업(프록시 HTML 등)은 비-2xx 면 005, 2xx 면 007 이다.
     */
    KDCA_GATEWAY_REJECTED("NOTIFIABLE_IMPORT_003",
        "공공데이터포털 게이트웨이가 요청을 거절했습니다. (operation=%s, httpStatus=%s, returnReasonCode=%s, returnAuthMsg=%s)", HttpStatus.BAD_GATEWAY),
    /** 원천이 JSON 으로 오류 결과 코드를 돌려줬다 (예: {@code 104 DATATYPE_PARAMETER_ERROR}). 요청 파라미터나 원천 상태의 문제다. */
    KDCA_API_ERROR("NOTIFIABLE_IMPORT_004", "질병관리청 API 가 오류를 돌려줬습니다. (operation=%s, resultCode=%s, resultMsg=%s)", HttpStatus.BAD_GATEWAY),
    /** XML 봉투가 아닌 비-2xx 응답. 게이트웨이 · 원천 장애로 본다. */
    KDCA_HTTP_ERROR("NOTIFIABLE_IMPORT_005", "질병관리청 API 가 오류 상태 코드를 돌려줬습니다. (operation=%s, httpStatus=%s)", HttpStatus.BAD_GATEWAY),
    /** I/O · timeout 으로 응답을 받지 못했다. reason 은 가장 안쪽 원인의 클래스 단순명이다. */
    KDCA_CALL_FAILED("NOTIFIABLE_IMPORT_006", "질병관리청 API 호출에 실패했습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),
    /** 응답은 받았지만 형식이 기대와 다르다 (봉투 · 필드 · 연도 · 주차 · 시도 코드 · 자연키 중복 · 페이지). 조용히 틀린 값을 적재하지 않으려고 실패시킨다. */
    KDCA_RESPONSE_INVALID("NOTIFIABLE_IMPORT_007", "질병관리청 응답을 해석할 수 없습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),

    // 실행 보호 — 010 ~ 019

    /** 실행당 호출 상한에 닿았다. 개발계정 일 1,000건 한도를 지키려는 장치라, 이후 요청은 부르지 않는다. */
    REQUEST_BUDGET_EXCEEDED("NOTIFIABLE_IMPORT_010", "실행당 질병관리청 API 호출 상한에 닿아 더 부르지 않습니다. (used=%s, maxCallsPerRun=%s)",
        HttpStatus.TOO_MANY_REQUESTS);

    // 020 ~ 은 잡 실행 요약(#85, 예: 일부 요청 실패로 실행을 FAILED 로 끝낸다)에 쓴다.

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
