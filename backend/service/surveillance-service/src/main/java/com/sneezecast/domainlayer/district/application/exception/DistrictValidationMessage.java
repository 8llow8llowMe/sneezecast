package com.sneezecast.domainlayer.district.application.exception;

/**
 * 행정동 요청 검증 메시지 카탈로그 (DISTRICT_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 코드와 메시지를 여기 모아 DTO · 컨트롤러가 참조하게
 * 하면 오타 · 삭제를 컴파일러가 잡는다. 형식은 {@code "코드:사용자 메시지"} — {@code ValidationErrorSupport} 가 접두어를 분리한다.
 */
public final class DistrictValidationMessage {

    /** 검색어 최대 길이 (앞뒤 공백을 걷어 낸 길이). 동 · 시도 · 시군구 이름을 이어 써도 넘지 않는 길이로, 그보다 긴 입력은 찾을 것이 없다. */
    public static final int SEARCH_QUERY_MAX_LENGTH = 20;

    /**
     * SGIS 읍면동 코드({@code adm_cd}) 형식 — 숫자 8자리. 행안부 10자리 코드는 체계가 달라 여기서 막는다 (entity-design §3-1).
     * {@code \d} 는 Java 기본 모드에서 ASCII 숫자만 받는다.
     */
    public static final String DISTRICT_CODE_REGEXP = "^\\d{8}$";

    public static final String SEARCH_QUERY_REQUIRED = "DISTRICT_101:검색어를 입력해주세요.";
    public static final String SEARCH_QUERY_LENGTH_INVALID = "DISTRICT_102:검색어는 " + SEARCH_QUERY_MAX_LENGTH + "자 이하여야 합니다.";
    public static final String DISTRICT_CODE_FORMAT_INVALID = "DISTRICT_103:행정동 코드는 숫자 8자리여야 합니다.";

    private DistrictValidationMessage() {
    }
}
