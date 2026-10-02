package com.sneezecast.domainlayer.notifiableimport.domain.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 조회 조건 하나({@link NotifiableRequest})의 결과. 페이지를 넘겼으면 모든 페이지를 합친 값이다. 적재 이력 한 행의 재료다 (entity-design §3-3).
 *
 * @param rows          해석한 행 ({@code 계} · 전국 행은 빠져 있다)
 * @param contentSha256 받은 페이지 본문을 순서대로 이어 붙인 바이트의 SHA-256 (소문자 16진수 64자)
 * @param byteLength    받은 본문 바이트 수의 합
 * @param rawRowCount   받은 원천 행 수 ({@code 계} · 전국 행 포함)
 * @param nullValueCount {@code rows} 중 값이 null 인 행 수 (원천이 비우거나 숫자가 아닌 값을 줬다)
 * @param calls         이 요청에 쓴 HTTP 호출 수 (= 페이지 수)
 * @param <T>           행 타입
 */
public record NotifiableFetch<T>(List<T> rows, String contentSha256, int byteLength, int rawRowCount, int nullValueCount, int calls) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public NotifiableFetch {
        rows = List.copyOf(rows);
        if (contentSha256 == null || !SHA256_HEX.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lowercase hex characters");
        }
        if (byteLength < 0 || calls < 1 || rawRowCount < rows.size() || nullValueCount < 0 || nullValueCount > rows.size()) {
            throw new IllegalArgumentException("invalid fetch counts. byteLength=%s rawRowCount=%s rows=%s nullValueCount=%s calls=%s"
                .formatted(byteLength, rawRowCount, rows.size(), nullValueCount, calls));
        }
    }
}
