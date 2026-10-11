package com.sneezecast.domainlayer.advisory.application.service.presenter;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryHistoryItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryOperatorCitationItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorDetailResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryDetailInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryHistoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 운영자 안내 Info → 응답 DTO. 값을 다시 계산하지 않는다 — ID 를 문자열로(coding-conventions §2-1), enum 을 metadata 로(§7), 시각을 초 단위 UTC
 * {@link Instant} 로(§2-2) 바꾸기만 한다. 검토 후보(#266) · 발행 이력(#267) 응답도 안내 한 건 · 이력 한 행은 여기의 {@link #toResponse} ·
 * {@link #toHistoryItem} 을 다시 쓴다.
 *
 * <p>저장 시각(Auditing · 발행 · 철회)은 JVM 기본 시간대의 지역 시각이다 — 같은 기준으로 읽어 순간으로 되돌린다({@link AdvisoryPresenter} 와 같다).
 */
@Component
public class AdvisoryOperatorPresenter {

    private final ZoneId storageZone;

    public AdvisoryOperatorPresenter() {
        this(ZoneId.systemDefault());
    }

    /** 테스트가 JVM 시간대와 무관하게 변환을 고정할 때 쓴다. */
    AdvisoryOperatorPresenter(ZoneId storageZone) {
        this.storageZone = storageZone;
    }

    public AdvisoryOperatorResponse toResponse(AdvisoryInfo info) {
        return AdvisoryOperatorResponse.builder()
            .advisoryId(String.valueOf(info.id()))
            .districtCode(info.districtCode())
            .isoWeek(info.isoWeek().value())
            .status(info.status().toMetadata())
            .title(info.title())
            .body(info.body())
            .citation(toCitationItem(info.citation()))
            .correctsAdvisoryId(info.correctsAdvisoryId() == null ? null : String.valueOf(info.correctsAdvisoryId()))
            .publishedAt(toInstant(info.publishedAt()))
            .retractedAt(toInstant(info.retractedAt()))
            .version(info.version())
            .createdAt(toInstant(info.createdAt()))
            .updatedAt(toInstant(info.updatedAt()))
            .build();
    }

    public AdvisoryOperatorDetailResponse toDetailResponse(AdvisoryDetailInfo info) {
        return new AdvisoryOperatorDetailResponse(toResponse(info.advisory()), info.histories().stream().map(this::toHistoryItem).toList());
    }

    public AdvisoryHistoryItem toHistoryItem(AdvisoryHistoryInfo info) {
        return AdvisoryHistoryItem.builder()
            .historyId(String.valueOf(info.id()))
            .fromStatus(info.fromStatus() == null ? null : info.fromStatus().toMetadata())
            .toStatus(info.toStatus().toMetadata())
            .title(info.title())
            .body(info.body())
            .operatorId(String.valueOf(info.operatorId()))
            .aiModel(info.aiModel())
            .promptVersion(info.promptVersion())
            .memo(info.memo())
            .createdAt(toInstant(info.createdAt()))
            .build();
    }

    private static AdvisoryOperatorCitationItem toCitationItem(AdvisoryCitation citation) {
        return AdvisoryOperatorCitationItem.builder()
            .level(citation.level().toMetadata())
            .participantCount(citation.participantCount())
            .symptomaticCount(citation.symptomaticCount())
            .respiratoryCount(citation.respiratoryCount())
            .entericCount(citation.entericCount())
            .ruleVersion(citation.ruleVersion())
            .build();
    }

    private Instant toInstant(LocalDateTime storedAt) {
        return storedAt == null ? null : storedAt.atZone(storageZone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
