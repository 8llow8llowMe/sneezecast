package com.sneezecast.domainlayer.advisory.application.service.presenter;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryHistoryItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryOperatorCitationItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryPublicationHistoryItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryReviewCandidateAdvisoryItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryReviewCandidateGroupItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.item.AdvisoryReviewCandidateItem;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorDetailResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryPublicationHistoryResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryReviewCandidateListResponse;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryDetailInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryHistoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.PublicationHistoryListInfo;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.AdvisoryState;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Candidate;
import com.sneezecast.domainlayer.advisory.application.info.ReviewCandidateListInfo.Measured;
import com.sneezecast.domainlayer.advisory.domain.enums.ReviewSignal;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 운영자 안내 Info → 응답 DTO. 값을 다시 계산하지 않는다 — ID 를 문자열로(coding-conventions §2-1), enum 을 metadata 로(§7), 시각을 초 단위 UTC
 * {@link Instant} 로(§2-2) 바꾸기만 한다. 정정 발행(#267) 응답은 새 안내 한 건({@link #toResponse})이고, 발행 이력(#267)도 안내 한 건 · 이력 한 행은
 * 여기의 {@link #toResponse} · {@link #toHistoryItem} 을 다시 쓴다({@link #toPublicationHistoryResponse}). 검토 후보(#266)는 안내의 ID · 상태 · 버전만 싣는다({@link #toCandidateListResponse}).
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

    /** 발행 이력(#267). 안내 한 건 · 이력 한 행은 상세와 같은 변환이다. */
    public AdvisoryPublicationHistoryResponse toPublicationHistoryResponse(PublicationHistoryListInfo info) {
        return AdvisoryPublicationHistoryResponse.builder()
            .fromIsoWeek(info.fromWeek().value())
            .toIsoWeek(info.toWeek().value())
            .entries(info.entries().stream().map(this::toPublicationHistoryItem).toList())
            .build();
    }

    private AdvisoryPublicationHistoryItem toPublicationHistoryItem(PublicationHistoryListInfo.Entry entry) {
        return AdvisoryPublicationHistoryItem.builder()
            .advisory(toResponse(entry.advisory()))
            .districtName(entry.districtName())
            .sigungu(entry.sigungu())
            .correctedByAdvisoryId(entry.correctedByAdvisoryId() == null ? null : String.valueOf(entry.correctedByAdvisoryId()))
            .histories(entry.histories().stream().map(this::toHistoryItem).toList())
            .build();
    }

    /** 검토 후보(#266). 자료 부족 후보는 Info 에 수치가 없어 응답 항목의 수치도 null 로 남고 {@code @JsonInclude(NON_NULL)} 로 키가 빠진다. */
    public AdvisoryReviewCandidateListResponse toCandidateListResponse(ReviewCandidateListInfo info) {
        return AdvisoryReviewCandidateListResponse.builder()
            .isoWeek(info.isoWeek().value())
            .finalized(info.finalized())
            .surgeChangePercent(info.surgeChangePercent())
            .repeatRevisedPercent(info.repeatRevisedPercent())
            .candidates(info.candidates().stream().map(AdvisoryOperatorPresenter::toCandidateItem).toList())
            .build();
    }

    private static AdvisoryReviewCandidateItem toCandidateItem(Candidate candidate) {
        Measured measured = candidate.measured();
        AdvisoryState advisory = candidate.advisory();
        return AdvisoryReviewCandidateItem.builder()
            .districtCode(candidate.districtCode())
            .districtName(candidate.districtName())
            .sigungu(candidate.sigungu())
            .active(candidate.active())
            .signals(candidate.signals().stream().map(ReviewSignal::toMetadata).toList())
            .level(candidate.level().toMetadata())
            .insufficientReason(candidate.insufficientReason() == null ? null : candidate.insufficientReason().toMetadata())
            .draftable(candidate.draftable())
            .participantCount(candidate.participantCount())
            .previousParticipantCount(candidate.previousParticipantCount())
            .revisedReportCount(candidate.revisedReportCount())
            .symptomaticCount(measured == null ? null : measured.symptomaticCount())
            .symptomRate(measured == null ? null : measured.symptomRate())
            .baselineRate(measured == null ? null : measured.baselineRate())
            .baselineDeltaPp(measured == null ? null : measured.baselineDeltaPp())
            .groups(measured == null ? null : measured.groups().stream()
                .map(group -> new AdvisoryReviewCandidateGroupItem(group.group().toMetadata(), group.count(), group.rate()))
                .toList())
            .advisory(advisory == null ? null : new AdvisoryReviewCandidateAdvisoryItem(String.valueOf(advisory.id()), advisory.status().toMetadata(),
                advisory.version()))
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
