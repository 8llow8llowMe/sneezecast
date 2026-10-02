package com.sneezecast.global.properties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 전수신고 감염병 발생현황 적재 설정.
 *
 * @param sidoCodes      {@code /Region} 을 부를 질병관리청 시도 코드 (SGIS 코드와 다르다 — 01 서울 … 17 세종, 18 전남광주). 이 순서대로 부른다.
 *                       값을 주지 않으면 01 ~ 18. 광주(05) · 전남(13) · 전남광주(18)가 모두 응답하므로 셋 다 받는다 (data-api-analysis §2-4)
 * @param maxCallsPerRun 실행당 호출 상한. 실행당 약 74회 + 페이지 여유. 개발계정 일 1,000건 한도를 지키려는 값이다
 */
@ConfigurationProperties(prefix = "notifiable-import")
public record NotifiableImportProperties(List<String> sidoCodes, @DefaultValue("100") int maxCallsPerRun) {

    public static final List<String> DEFAULT_SIDO_CODES = List.of(
        "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15", "16", "17", "18");

    private static final Pattern SIDO_CODE_PATTERN = Pattern.compile("\\d{2}");
    private static final String NATION_SIDO_CODE = "00";

    public NotifiableImportProperties {
        if (sidoCodes == null) {
            sidoCodes = DEFAULT_SIDO_CODES;
        }
        if (sidoCodes.isEmpty()) {
            throw new IllegalArgumentException("notifiable-import.sido-codes must not be empty");
        }
        Set<String> seen = new HashSet<>();
        for (String sidoCode : sidoCodes) {
            // 00 은 전국 행만 준다 — 주별 전국과 겹치고 시도 값이 아니다.
            if (sidoCode == null || !SIDO_CODE_PATTERN.matcher(sidoCode).matches() || NATION_SIDO_CODE.equals(sidoCode)) {
                throw new IllegalArgumentException("notifiable-import.sido-codes must be 2 digits other than 00. value=" + sidoCode);
            }
            if (!seen.add(sidoCode)) {
                throw new IllegalArgumentException("notifiable-import.sido-codes must not contain duplicates. value=" + sidoCode);
            }
        }
        sidoCodes = List.copyOf(sidoCodes);
        if (maxCallsPerRun < 1) {
            throw new IllegalArgumentException("notifiable-import.max-calls-per-run must be positive. value=" + maxCallsPerRun);
        }
    }
}
