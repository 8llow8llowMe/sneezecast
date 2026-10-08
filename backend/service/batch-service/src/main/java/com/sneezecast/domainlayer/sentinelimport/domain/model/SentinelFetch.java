package com.sneezecast.domainlayer.sentinelimport.domain.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 조회 조건 하나({@link SentinelRequest})의 결과. 적재 이력 한 행의 재료다 (entity-design §3-3).
 *
 * <p>원천 한 행이 열 수만큼의 행이 되므로 {@code rawRowCount} 는 {@code rows} 수보다 작다 — {@code rawRowCount} 는 응답 {@code data} 의
 * 행 수(주 또는 연령대 수)다.
 *
 * @param rows           해석한 행
 * @param contentSha256  데이터 응답 본문의 SHA-256 (소문자 16진수 64자). 화면 요청 응답은 세션 쿠키만 쓰고 버리므로 넣지 않는다
 * @param byteLength     데이터 응답 본문 바이트 수
 * @param rawRowCount    응답 {@code data} 의 행 수
 * @param calls          이 요청에 쓴 HTTP 호출 수 (화면 + 데이터)
 * @param nullValueCount {@code rows} 중 값이 null 인 행 수 (원천이 비우거나 음수를 줬다)
 * @param pendingCount   {@code 집계 중} 이라 행을 만들지 않은 칸 수 (진행 중인 주 — 결측과 다르다)
 * @param <T>            행 타입
 */
public record SentinelFetch<T>(List<T> rows, String contentSha256, int byteLength, int rawRowCount, int calls, int nullValueCount, int pendingCount) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public SentinelFetch {
        rows = List.copyOf(rows);
        if (contentSha256 == null || !SHA256_HEX.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lowercase hex characters");
        }
        if (byteLength < 0 || rawRowCount < 0 || calls < 1 || nullValueCount < 0 || nullValueCount > rows.size() || pendingCount < 0) {
            throw new IllegalArgumentException("invalid fetch counts. byteLength=%s rawRowCount=%s rows=%s calls=%s nullValueCount=%s pendingCount=%s"
                .formatted(byteLength, rawRowCount, rows.size(), calls, nullValueCount, pendingCount));
        }
    }
}
