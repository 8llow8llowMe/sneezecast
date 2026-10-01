package com.sneezecast.domainlayer.districtimport.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 행정동 마스터 적재 에러코드.
 *
 * <p>batch-service 에는 웹 API 가 없어 예외 핸들러를 두지 않는다. 예외는 Step 을 FAILED 로 끝내고 메시지가 배치 메타데이터 · 로그에 남는다.
 * {@code httpStatus} 는 컨벤션의 3필드 형식을 맞추려는 것이고 응답으로 나가지 않는다.
 *
 * <p><b>메시지 자리 표시자에 인증키 · 토큰을 넣지 않는다.</b> 요청 URL 에 둘 다 실리므로 URL 이나 하위 예외 메시지도 넣지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum DistrictImportErrorCode {

    // 원천 (SGIS)
    SGIS_CREDENTIALS_MISSING("DISTRICT_IMPORT_001", "SGIS 인증키가 설정되지 않았습니다. (sgis.consumer-key / SGIS_CONSUMER_KEY, sgis.consumer-secret / SGIS_CONSUMER_SECRET)",
        HttpStatus.INTERNAL_SERVER_ERROR),
    SGIS_AUTH_FAILED("DISTRICT_IMPORT_002", "SGIS 인증에 실패했습니다. (httpStatus=%s, errCd=%s)", HttpStatus.BAD_GATEWAY),
    SGIS_TOKEN_REJECTED("DISTRICT_IMPORT_003", "다시 발급한 SGIS 토큰도 거부됐습니다. (operation=%s)", HttpStatus.BAD_GATEWAY),
    SGIS_API_ERROR("DISTRICT_IMPORT_004", "SGIS API 가 오류를 돌려줬습니다. (operation=%s, errCd=%s, errMsg=%s)", HttpStatus.BAD_GATEWAY),
    SGIS_HTTP_ERROR("DISTRICT_IMPORT_005", "SGIS API 가 오류 상태 코드를 돌려줬습니다. (operation=%s, httpStatus=%s)", HttpStatus.BAD_GATEWAY),
    SGIS_CALL_FAILED("DISTRICT_IMPORT_006", "SGIS API 호출에 실패했습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),
    SGIS_RESPONSE_INVALID("DISTRICT_IMPORT_007", "SGIS 응답을 해석할 수 없습니다. (operation=%s, reason=%s)", HttpStatus.BAD_GATEWAY),

    // 스냅샷 검증 · 보호 규칙 — 아무것도 쓰기 전에 실패한다
    YEAR_REGRESSION("DISTRICT_IMPORT_010", "마지막으로 적재한 기준 연도보다 과거 연도로는 적재하지 않습니다. (year=%s, lastLoadedYear=%s)", HttpStatus.CONFLICT),
    SNAPSHOT_EMPTY("DISTRICT_IMPORT_011", "SGIS 스냅샷이 비어 있습니다. (year=%s)", HttpStatus.BAD_GATEWAY),
    SNAPSHOT_DUPLICATE_CODE("DISTRICT_IMPORT_012", "SGIS 스냅샷에 같은 읍면동 코드가 두 번 있습니다. (year=%s, code=%s)", HttpStatus.BAD_GATEWAY),
    SNAPSHOT_SIDO_MISSING("DISTRICT_IMPORT_013", "SGIS 스냅샷에 읍면동이 하나도 없는 시도가 있습니다 — 응답이 잘렸을 수 있습니다. (year=%s, sidoCodes=%s)",
        HttpStatus.BAD_GATEWAY),
    MASS_RETIRE_BLOCKED("DISTRICT_IMPORT_014",
        "폐지될 행정동 비율이 임계값을 넘어 적재하지 않았습니다. 원인을 확인한 뒤 allowMassRetire=true 로 다시 실행하세요. (year=%s, retiring=%s, active=%s, ratio=%s, maxRatio=%s)",
        HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
