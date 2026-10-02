package com.sneezecast.domainlayer.official.domain.model;

import static com.sneezecast.domainlayer.official.OfficialFixtures.RUN_STARTED_AT;
import static com.sneezecast.domainlayer.official.OfficialFixtures.SHA256;
import static com.sneezecast.domainlayer.official.OfficialFixtures.ariDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.domain.enums.IngestChannel;
import com.sneezecast.domainlayer.official.domain.enums.IngestStatus;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 수집 기록 초안({@link OfficialSnapshotDraft})과 INSERT 할 행({@link OfficialSourceSnapshot})의 불변식. */
class OfficialSnapshotDraftTest {

    @Test
    @DisplayName("requestKey 는 비면 안 되고 200자를 넘으면 안 된다")
    void requestKeyIsRequiredAndBounded() {
        assertThatCode(() -> ariDraft("k".repeat(200), 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ariDraft(" ", 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestKey");
        assertThatThrownBy(() -> ariDraft("k".repeat(201), 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
    }

    @Test
    @DisplayName("본문 해시는 없어도(null) 되고, 있으면 소문자 16진수 64자다 — CHAR(64)")
    void contentSha256IsOptionalHex() {
        assertThatCode(() -> draft(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> draft(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "A".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft(OfficialSource.KDCA_SENTINEL, OfficialProgram.ARI, "a".repeat(63)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("프로그램은 원천에 속해야 한다 — 전수신고는 NOTIFIABLE, 표본감시는 나머지")
    void programBelongsToSource() {
        assertThatCode(() -> draft(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE, SHA256)).doesNotThrowAnyException();
        assertThatThrownBy(() -> draft(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.ARI, SHA256)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft(OfficialSource.KDCA_SENTINEL, OfficialProgram.NOTIFIABLE, SHA256))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("바이트 수 · 행 수는 음수가 아니다")
    void countsAreNotNegative() {
        assertThatThrownBy(() -> ariDraft("ari", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IMPORTED 기록은 실패 코드가 없고, FAILED 기록은 실패 코드가 있고 imported_count 가 0 이다")
    void snapshotStatusInvariants() {
        OfficialSnapshotDraft draft = ariDraft("ari", 3);

        assertThat(OfficialSourceSnapshot.imported(1L, draft, 3, RUN_STARTED_AT).errorCode()).isNull();
        OfficialSourceSnapshot failed = OfficialSourceSnapshot.failed(2L, draft, "SENTINEL_SCHEMA_CHANGED", RUN_STARTED_AT);
        assertThat(failed.status()).isEqualTo(IngestStatus.FAILED);
        assertThat(failed.importedCount()).isZero();

        assertThatThrownBy(() -> new OfficialSourceSnapshot(1L, draft, IngestStatus.IMPORTED, 3, "X", RUN_STARTED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OfficialSourceSnapshot(1L, draft, IngestStatus.FAILED, 3, "X", RUN_STARTED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfficialSourceSnapshot.failed(1L, draft, " ", RUN_STARTED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfficialSourceSnapshot.failed(1L, draft, "E".repeat(51), RUN_STARTED_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static OfficialSnapshotDraft draft(OfficialSource source, OfficialProgram program, String sha256) {
        return new OfficialSnapshotDraft(source, program, "key", IngestChannel.OPEN_API, sha256, 0, 0, RUN_STARTED_AT);
    }
}
