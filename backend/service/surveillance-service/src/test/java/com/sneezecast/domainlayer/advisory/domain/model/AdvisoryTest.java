package com.sneezecast.domainlayer.advisory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdvisoryTest {

    private static final ReportWeek W40 = ReportWeek.parse("2026-W40");
    private static final AdvisoryCitation CITATION = new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.SLIGHT, "2026-10-08");
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final LocalDateTime RETRACTED_AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Test
    @DisplayName("새 초안은 DRAFT · 버전 0 이고 발행 · 철회 시각과 정정 대상이 없다")
    void newDraft() {
        Advisory draft = draft();

        assertThat(draft.status()).isEqualTo(AdvisoryStatus.DRAFT);
        assertThat(draft.version()).isZero();
        assertThat(draft.publishedAt()).isNull();
        assertThat(draft.retractedAt()).isNull();
        assertThat(draft.correctsAdvisoryId()).isNull();
        assertThat(draft.citation()).isEqualTo(CITATION);
    }

    @Test
    @DisplayName("수정 → 승인 → 발행 → 철회가 상태 · 시각을 채우고, 근거 집계 · 인용 수치 · 동네 · 주는 그대로다")
    void lifecycleKeepsCitation() {
        Advisory edited = draft().edit("고친 제목", "고친 본문");
        Advisory published = edited.approve().publish(PUBLISHED_AT);
        Advisory retracted = published.retract(RETRACTED_AT);

        assertThat(edited.status()).isEqualTo(AdvisoryStatus.EDITED);
        assertThat(edited.title()).isEqualTo("고친 제목");
        assertThat(edited.body()).isEqualTo("고친 본문");
        assertThat(published.status()).isEqualTo(AdvisoryStatus.PUBLISHED);
        assertThat(published.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(retracted.status()).isEqualTo(AdvisoryStatus.RETRACTED);
        assertThat(retracted.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(retracted.retractedAt()).isEqualTo(RETRACTED_AT);
        assertThat(retracted).extracting(Advisory::aggregateId, Advisory::districtCode, Advisory::isoWeek, Advisory::citation)
            .containsExactly(7L, "11240660", W40, CITATION);
    }

    @Test
    @DisplayName("승인 뒤 수정하면 EDITED 로 돌아가 바로 발행할 수 없다")
    void editAfterApprovalNeedsReapproval() {
        Advisory reedited = draft().approve().edit("다시 고친 제목", "다시 고친 본문");

        assertThat(reedited.status()).isEqualTo(AdvisoryStatus.EDITED);
        assertThatThrownBy(() -> reedited.publish(PUBLISHED_AT)).isInstanceOf(IllegalStateException.class);
        assertThat(reedited.approve().publish(PUBLISHED_AT).status()).isEqualTo(AdvisoryStatus.PUBLISHED);
    }

    @Test
    @DisplayName("표에 없는 전이는 IllegalStateException 이다 — 초안 발행 · 승인 전 철회 · 발행본 수정 · 철회 뒤 처리")
    void invalidTransitionsThrow() {
        Advisory published = draft().approve().publish(PUBLISHED_AT);
        Advisory retracted = published.retract(RETRACTED_AT);

        assertThatThrownBy(() -> draft().publish(PUBLISHED_AT)).isInstanceOf(IllegalStateException.class).hasMessageContaining("DRAFT -> PUBLISHED");
        assertThatThrownBy(() -> draft().approve().retract(RETRACTED_AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> published.edit("제목", "본문")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> published.approve()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retracted.edit("제목", "본문")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retracted.retract(RETRACTED_AT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정정의 새 안내는 APPROVED · 버전 0 이고 근거 집계 · 동네 · 주 · 인용 수치를 철회된 이전 안내에서 옮겨 그 안내를 가리킨다 — 바로 발행할 수 있다")
    void newCorrectionCopiesFromRetracted() {
        Advisory retracted = draft().approve().publish(PUBLISHED_AT).retract(RETRACTED_AT);

        Advisory correction = Advisory.newCorrection(2L, retracted, "고친 제목", "고친 본문");

        assertThat(correction.status()).isEqualTo(AdvisoryStatus.APPROVED);
        assertThat(correction.version()).isZero();
        assertThat(correction.correctsAdvisoryId()).isEqualTo(1L);
        assertThat(correction).extracting(Advisory::id, Advisory::aggregateId, Advisory::districtCode, Advisory::isoWeek, Advisory::citation, Advisory::title,
            Advisory::body).containsExactly(2L, 7L, "11240660", W40, CITATION, "고친 제목", "고친 본문");
        assertThat(correction.publishedAt()).isNull();
        assertThat(correction.publish(RETRACTED_AT).status()).isEqualTo(AdvisoryStatus.PUBLISHED);
    }

    @Test
    @DisplayName("정정은 철회된 안내로만 만든다 — 발행 중 · 승인 안내로 부르면 IllegalStateException(철회가 먼저). 제목 · 본문 조건은 같다")
    void newCorrectionRequiresRetractedPrevious() {
        Advisory published = draft().approve().publish(PUBLISHED_AT);

        assertThatThrownBy(() -> Advisory.newCorrection(2L, published, "제목", "본문")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Advisory.newCorrection(2L, draft().approve(), "제목", "본문")).isInstanceOf(IllegalStateException.class);
        Advisory retracted = published.retract(RETRACTED_AT);
        assertThatThrownBy(() -> Advisory.newCorrection(2L, retracted, " ", "본문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Advisory.newCorrection(2L, retracted, "제목", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("제목은 공백이 아닌 100자(코드포인트) 이하, 본문은 공백이 아니어야 한다")
    void contentRules() {
        String hundredEmoji = "😷".repeat(Advisory.TITLE_MAX_LENGTH);

        assertThat(Advisory.newDraft(1L, 7L, "11240660", W40, CITATION, hundredEmoji, "본문").title()).isEqualTo(hundredEmoji);
        assertThatThrownBy(() -> Advisory.newDraft(1L, 7L, "11240660", W40, CITATION, hundredEmoji + "a", "본문"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Advisory.newDraft(1L, 7L, "11240660", W40, CITATION, " ", "본문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft().edit("제목", "\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상태와 발행 · 철회 시각이 어긋난 행은 만들 수 없다 (DB 에서 읽은 행도 같다)")
    void statusAndTimestampsMustAgree() {
        assertThatThrownBy(() -> draft().toBuilder().publishedAt(PUBLISHED_AT).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft().toBuilder().status(AdvisoryStatus.PUBLISHED).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft().toBuilder().status(AdvisoryStatus.RETRACTED).publishedAt(PUBLISHED_AT).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("인용 수치에는 자료 부족 단계가 들어갈 수 없고, 수치 관계가 집계와 같아야 한다")
    void citationRules() {
        assertThatThrownBy(() -> new AdvisoryCitation(150, 30, 20, 15, AggregateLevel.INSUFFICIENT, "2026-10-08"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvisoryCitation(10, 30, 20, 15, AggregateLevel.HIGH, "2026-10-08")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvisoryCitation(150, 30, 10, 10, AggregateLevel.HIGH, "2026-10-08")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이력은 안내의 바뀐 뒤 제목 · 본문과 요청한 운영자를 남기고, 운영자 ID 는 양수 · 메모는 500자 이하다")
    void historyRules() {
        Advisory edited = draft().edit("고친 제목", "고친 본문");

        AdvisoryHistory history = AdvisoryHistory.operatorTransition(9L, edited, AdvisoryStatus.DRAFT, 42L, "오탈자");

        assertThat(history).extracting(AdvisoryHistory::advisoryId, AdvisoryHistory::fromStatus, AdvisoryHistory::toStatus, AdvisoryHistory::title,
            AdvisoryHistory::body, AdvisoryHistory::operatorId, AdvisoryHistory::memo)
            .containsExactly(1L, AdvisoryStatus.DRAFT, AdvisoryStatus.EDITED, "고친 제목", "고친 본문", 42L, "오탈자");
        assertThat(history.aiModel()).isNull();
        assertThatThrownBy(() -> AdvisoryHistory.operatorTransition(9L, edited, AdvisoryStatus.DRAFT, 0L, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(AdvisoryHistory.operatorTransition(9L, edited, AdvisoryStatus.DRAFT, 42L, "가".repeat(500)).memo()).hasSize(500);
        assertThatThrownBy(() -> AdvisoryHistory.operatorTransition(9L, edited, AdvisoryStatus.DRAFT, 42L, "가".repeat(501)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Advisory draft() {
        return Advisory.newDraft(1L, 7L, "11240660", W40, CITATION, "가락1동 호흡기 증상 보고가 늘었어요", "손 씻기와 기침 예절을 지켜 주세요.");
    }
}
