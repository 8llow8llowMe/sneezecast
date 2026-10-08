package com.sneezecast.domainlayer.sentinelimport.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 감염병포털 표본감시 적재 에러코드.
 *
 * <p>batch-service 에는 웹 API 가 없어 예외 핸들러를 두지 않는다. 예외는 Step 을 FAILED 로 끝내거나 요청 하나의 적재 이력
 * ({@code official_source_snapshot.error_code})에 남는다. {@code httpStatus} 는 컨벤션의 3필드 형식을 맞추려는 것이고 응답으로 나가지 않는다.
 *
 * <p><b>어느 코드가 실행 전체를 멈추는지는 여기서 정하지 않는다</b> — 잡을 조립하는 Facade 가 정한다 (#157). 각 코드의 javadoc 에는 뜻만 적는다.
 *
 * <p>메시지에는 원천이 준 짧은 값만 잘라 넣고 응답 본문 · 요청 URL 은 넣지 않는다 (coding-conventions §10).
 */
@Getter
@RequiredArgsConstructor
public enum SentinelImportErrorCode {

    // 원천 (감염병포털 화면 데이터) — 001 ~ 009

    /** 비-2xx 응답. 포털 장애 · 차단으로 본다. */
    PORTAL_HTTP_ERROR("SENTINEL_IMPORT_001", "감염병포털이 오류 상태 코드를 돌려줬습니다. (operation=%s, httpStatus=%s)", HttpStatus.BAD_GATEWAY),
    /** I/O · timeout 으로 응답을 받지 못했다. reason 은 가장 안쪽 원인의 클래스 단순명이다 (요청 간격을 안 지키면 연결이 끊긴다, data-api-analysis §3-5). */
    PORTAL_CALL_FAILED("SENTINEL_IMPORT_002", "감염병포털 호출에 실패했습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),
    /** 화면 응답에 세션 쿠키가 없다. 쿠키 없이 데이터를 요청하면 데이터가 아니라 화면 · 오류가 온다. */
    PORTAL_SESSION_MISSING("SENTINEL_IMPORT_003", "감염병포털 화면 응답에 세션 쿠키가 없습니다. (operation=%s)", HttpStatus.BAD_GATEWAY),
    /** 응답은 받았지만 내용이 기대와 다르다 (JSON 아님 · 봉투 · 행 형식 · 요청 범위 밖 · 중복 · 컬럼 제약). 조용히 틀린 값을 적재하지 않으려고 실패시킨다. */
    RESPONSE_INVALID("SENTINEL_IMPORT_004", "감염병포털 응답을 해석할 수 없습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),
    /**
     * 화면 데이터의 열 구성이 바뀌었다 ({@code captionList} 불일치 · 열 수 · 연령대 라벨 · {@code GR2} 와 열 제목 불일치). 공개 API 가 아니라
     * 열 순서로 병원체 코드를 붙이므로, 형식이 바뀌면 다른 병원체에 값이 들어가기 전에 멈춘다 (data-api-analysis §3-4). 기대 목록은
     * {@code SentinelPathogenCatalog} 이고 고치려면 배포해야 한다.
     */
    SCHEMA_CHANGED("SENTINEL_IMPORT_005", "감염병포털 화면 데이터 형식이 바뀌었습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),

    // 실행 보호 — 010 ~ 019

    /** 실행당 호출 상한에 닿았다. 원천에 부담을 주지 않으려는 장치라, 이후 요청은 부르지 않는다. */
    REQUEST_BUDGET_EXCEEDED("SENTINEL_IMPORT_010", "실행당 감염병포털 호출 상한에 닿아 더 부르지 않습니다. (used=%s, maxCallsPerRun=%s)",
        HttpStatus.TOO_MANY_REQUESTS),

    // 실행 요약 — 020 ~ 029

    /**
     * 실행 하나에서 실패한 요청이 있다. 실패한 요청마다 FAILED 적재 이력이 남아 있고, 이 예외는 Step · Job 을 FAILED 로 끝내 알리는 몫이다.
     * {@code aborted=true} 면 {@code abortedBy} 코드 때문에 남은 요청({@code notAttempted})을 부르지 않았다. {@code failedRequestKeys} 는 앞에서부터
     * 최대 20개다.
     */
    RUN_FAILED("SENTINEL_IMPORT_020",
        "표본감시 적재에서 실패한 요청이 있습니다. (planned=%s, imported=%s, failed=%s, notAttempted=%s, aborted=%s, abortedBy=%s, failedRequestKeys=%s)",
        HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
