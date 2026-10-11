package com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 안내 이력 한 행 (advisory_history, entity-design §2-4) — 생성 · 전이 · 수정마다 하나. 운영자 화면 전용이다. 사유({@code memo})는 운영자에게만
 * 보이고 공개 안내 API 는 이력을 읽지 않는다.
 */
@Builder
@Schema(description = "안내 이력 항목 DTO")
public record AdvisoryHistoryItem(
    @Schema(description = "이력 ID", example = "7250000000000000001")
    String historyId,

    @Schema(description = "이전 상태. 초안 생성이면 null", example = "{\"code\":\"APPROVED\",\"name\":\"승인됨\",\"description\":\"운영자가 현재 본문을 검토 · 승인했다. 승인 뒤 고치면 다시 승인받는다\"}")
    CodeNameDescriptionMetadata fromStatus,

    @Schema(description = "바뀐 상태", example = "{\"code\":\"PUBLISHED\",\"name\":\"발행됨\",\"description\":\"공개 안내로 보인다. 본문을 고치지 않는다 — 철회하고 새 안내를 만든다\"}")
    CodeNameDescriptionMetadata toStatus,

    @Schema(description = "이 시점의 제목", example = "가락1동 발열 · 기침 보고가 지난 4주보다 많이 늘었어요")
    String title,

    @Schema(description = "이 시점의 본문 — 무엇이 승인 · 발행됐는지 복원한다", example = "손을 자주 씻고 기침할 때는 옷소매로 가려 주세요.")
    String body,

    @Schema(description = "처리한 운영자의 회원 ID (토큰 sub). 표시 이름은 surveillance 에 없다", example = "9001")
    String operatorId,

    @Schema(description = "AI 초안 모델 id. AI 초안 전이에만 있다(아직 경로 없음)")
    String aiModel,

    @Schema(description = "AI 초안 프롬프트 버전. AI 초안 전이에만 있다(아직 경로 없음)")
    String promptVersion,

    @Schema(description = "수정 · 철회 사유 (운영자 메모). 없으면 null. 사용자 화면에는 보이지 않는다", example = "집계 오류 확인")
    String memo,

    @Schema(description = "전이 시각 (ISO-8601 UTC, 초 단위)", example = "2026-10-06T01:00:00Z")
    Instant createdAt
) {

}
