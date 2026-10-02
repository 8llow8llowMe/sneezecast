package com.sneezecast.domainlayer.official.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.IngestChannel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 원천 요청 하나(조회 조건 하나)의 수집 기록 — {@code official_source_snapshot} 의 결과 컬럼(status · imported_count · error_code)을 뺀 부분이다
 * (entity-design §3-3). 결과는 쓰는 쪽({@code OfficialIngestProcessor})이 채운다.
 *
 * <p>{@code program} 은 {@code source} 에 속해야 한다 — 전수신고는 NOTIFIABLE 하나, 표본감시는 나머지다 (entity-design §3-2 표).
 *
 * @param requestKey    조회 조건 요약 (예: {@code ari:2026-31~2026-38:age=ALL}). 인증키 · URL 을 넣지 않는다
 * @param contentSha256 받은 본문의 SHA-256 (소문자 16진수 64자). 본문을 받지 못했으면 null
 * @param byteLength    받은 바이트 수
 * @param rowCount      파싱한 원천 행 수
 * @param runStartedAt  실행 시작 시각 (JobParameter {@code runAt})
 */
public record OfficialSnapshotDraft(
    OfficialSource source,
    OfficialProgram program,
    String requestKey,
    IngestChannel channel,
    String contentSha256,
    int byteLength,
    int rowCount,
    LocalDateTime runStartedAt
) {

    private static final int REQUEST_KEY_MAX_LENGTH = 200;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public OfficialSnapshotDraft {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(runStartedAt, "runStartedAt");
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey must not be blank.");
        }
        if (requestKey.codePointCount(0, requestKey.length()) > REQUEST_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("requestKey must be at most %d characters. length=%d".formatted(REQUEST_KEY_MAX_LENGTH, requestKey.length()));
        }
        if (contentSha256 != null && !SHA256_PATTERN.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lowercase hex characters. requestKey=" + requestKey);
        }
        if (byteLength < 0 || rowCount < 0) {
            throw new IllegalArgumentException("byteLength and rowCount must not be negative. byteLength=%d rowCount=%d requestKey=%s"
                .formatted(byteLength, rowCount, requestKey));
        }
        if ((source == OfficialSource.KDCA_NOTIFIABLE) != (program == OfficialProgram.NOTIFIABLE)) {
            throw new IllegalArgumentException("program does not belong to source. source=%s program=%s requestKey=%s"
                .formatted(source, program, requestKey));
        }
    }
}
