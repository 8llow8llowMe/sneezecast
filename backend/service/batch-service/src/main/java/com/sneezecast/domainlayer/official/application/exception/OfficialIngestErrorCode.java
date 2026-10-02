package com.sneezecast.domainlayer.official.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 공식 감시 자료 적재(쓰기 경로) 에러코드. 원천 호출 · 파싱 오류는 각 잡의 에러코드다.
 *
 * <p>batch-service 에는 웹 API 가 없어 예외 핸들러를 두지 않는다. 예외는 Step 을 FAILED 로 끝내고 메시지가 배치 메타데이터 · 로그에 남는다.
 * {@code httpStatus} 는 컨벤션의 3필드 형식을 맞추려는 것이고 응답으로 나가지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum OfficialIngestErrorCode {

    // 쓰기 전 검증 — 행은 쓰지 않고, 코드가 FAILED 수집 기록의 error_code 로 남는다
    SOURCE_MISMATCH("OFFICIAL_INGEST_001", "적재 행의 원천 · 프로그램이 수집 기록과 다릅니다. (requestKey=%s, expected=%s/%s, actual=%s/%s)",
        HttpStatus.INTERNAL_SERVER_ERROR),
    DUPLICATE_NATURAL_KEY("OFFICIAL_INGEST_002", "한 적재에 같은 자연키 행이 두 번 있습니다. (requestKey=%s, naturalKey=%s)", HttpStatus.BAD_GATEWAY),

    // 쓰기 — 롤백 뒤 FAILED 수집 기록의 error_code 로 코드만 남는다. 예외로 던지지 않고 원래 예외를 그대로 다시 던진다.
    WRITE_FAILED("OFFICIAL_INGEST_003", "공식 감시 자료 쓰기가 실패해 롤백했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
