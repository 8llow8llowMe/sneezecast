package com.sneezecast.domainlayer.advisory.application.exception;

/**
 * 예방 안내 요청 검증 메시지 카탈로그 (ADVISORY_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 형식은 {@code "코드:사용자 메시지"} —
 * {@code ValidationErrorSupport} 가 접두어를 분리한다.
 */
public final class AdvisoryValidationMessage {

    /** SGIS 읍면동 코드 형식 — 숫자 8자리 ({@code AggregateValidationMessage#DISTRICT_CODE_REGEXP} 와 같다). {@code \d} 는 ASCII 숫자만 받는다. */
    public static final String DISTRICT_CODE_REGEXP = "^\\d{8}$";

    /**
     * ISO 주 {@code YYYY-Www} — 주 번호는 01~53 만 받는다 ({@code AggregateValidationMessage#ISO_WEEK_REGEXP} 와 같다). 53주가 없는 해의 W53 은 형식으로
     * 거를 수 없어 조회 단계의 {@code ADVISORY_009} 다.
     */
    public static final String ISO_WEEK_REGEXP = "^\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])$";

    // 빈 문자열 · 공백은 형식이 잡는다 — 같은 입력에 두 오류가 겹쳐 나오지 않게 필수는 @NotNull 로만 본다 (공개 집계 지도 요청과 같다).
    public static final String DISTRICT_CODE_REQUIRED = "ADVISORY_101:행정동 코드를 입력해주세요.";
    public static final String DISTRICT_CODE_FORMAT_INVALID = "ADVISORY_102:행정동 코드는 숫자 8자리여야 합니다.";
    public static final String ISO_WEEK_REQUIRED = "ADVISORY_103:주를 입력해주세요. (예: 2026-W41)";
    public static final String ISO_WEEK_FORMAT_INVALID = "ADVISORY_104:주는 YYYY-Www 형식이어야 합니다. (예: 2026-W41)";

    /*
     * 운영자 API(#246) 요청 본문. 도메인(Advisory · AdvisoryHistory · AdvisoryRetractCommand)이 같은 조건을 IllegalArgumentException 으로 다시 보지만
     * 그것은 프로그래밍 오류 안전망이다 — 사용자 입력은 여기서 400 으로 거른다(500 으로 새지 않게).
     *
     * 길이는 @Size(UTF-16 단위)로 잰다. 도메인 · 컬럼(utf8mb4)은 문자(코드포인트) 수라, 이모지처럼 두 단위인 문자는 웹 경계에서 2자로 세어 조금 더 엄격하다
     * — 웹 경계를 지난 값은 언제나 도메인 · 컬럼 상한 안이다.
     */

    /**
     * 공백이 아닌 문자가 하나 이상 있는지 — 제목 · 본문 · 사유에 {@code @NotBlank} 와 함께 건다. {@code @NotBlank} 는 {@code String#trim} 기준이라
     * U+0020 이하만 공백으로 보고, 유니코드 공백(U+3000 전각 공백, U+2000~200A, U+2028 · 2029, U+1680, U+205F 등)만 넣은 값을 지나보낸다 — 그 값이 도메인
     * {@code String#isBlank}({@link Character#isWhitespace})에서 {@code IllegalArgumentException} 이 되어 봉투 없는 500 이 났다.
     *
     * <p>"공백 아님" 은 유니코드 공백({@code (?U)\s} = White_Space 속성)도 아니고 Java 공백({@code \p{javaWhitespace}} = 도메인 {@code isBlank} 의 기준 —
     * U+001C~001F 를 포함한다)도 아닌 문자다. 둘을 함께 빼므로 이 패턴을 지난 값은 도메인 {@code isBlank} 도 지난다. 줄바꿈하지 않는 공백(U+00A0 등)만 넣은 값은
     * 도메인보다 엄격하게 거른다(사람이 읽을 글자가 없다).
     */
    public static final String HAS_VISIBLE_CHARACTER_REGEXP = "(?U)(?s).*[^\\s\\p{javaWhitespace}].*";

    /** 제목 상한 — {@code advisory.title} VARCHAR(100) ({@link com.sneezecast.domainlayer.advisory.domain.model.Advisory#TITLE_MAX_LENGTH}). */
    public static final int TITLE_MAX_LENGTH = 100;

    /**
     * 본문 상한 — {@code advisory.body} · {@code advisory_history.body} 가 MySQL {@code TEXT}(65,535 바이트)라 utf8mb4 최대 4바이트로 나눈 16,383자다
     * (16,383 × 4 = 65,532). 화면의 초안 상한(500자)보다 훨씬 크고, 컬럼을 넘는 값이 DB 오류(500)가 되지 않게 막는 상한이다.
     */
    public static final int BODY_MAX_LENGTH = 16_383;

    /** 메모 · 사유 상한 — {@code advisory_history.memo} VARCHAR(500) ({@link com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory#MEMO_MAX_LENGTH}). */
    public static final int MEMO_MAX_LENGTH = 500;

    public static final String TITLE_REQUIRED = "ADVISORY_105:제목을 입력해주세요.";
    public static final String TITLE_TOO_LONG = "ADVISORY_106:제목은 100자 이하여야 합니다.";
    public static final String BODY_REQUIRED = "ADVISORY_107:본문을 입력해주세요.";
    public static final String BODY_TOO_LONG = "ADVISORY_108:본문은 16,383자 이하여야 합니다.";
    public static final String MEMO_TOO_LONG = "ADVISORY_109:수정 메모는 500자 이하여야 합니다.";
    public static final String REASON_REQUIRED = "ADVISORY_110:사유를 입력해주세요.";
    public static final String REASON_TOO_LONG = "ADVISORY_111:사유는 500자 이하여야 합니다.";
    public static final String VERSION_REQUIRED = "ADVISORY_112:안내 버전(version)을 보내주세요.";
    public static final String VERSION_INVALID = "ADVISORY_113:안내 버전(version)은 0 이상이어야 합니다.";

    private AdvisoryValidationMessage() {
    }
}
