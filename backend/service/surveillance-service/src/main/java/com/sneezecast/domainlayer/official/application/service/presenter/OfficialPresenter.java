package com.sneezecast.domainlayer.official.application.service.presenter;

import com.sneezecast.domainlayer.official.adapter.in.web.dto.item.OfficialDiseaseItem;
import com.sneezecast.domainlayer.official.adapter.in.web.dto.item.OfficialProgramItem;
import com.sneezecast.domainlayer.official.adapter.in.web.dto.item.OfficialValueItem;
import com.sneezecast.domainlayer.official.adapter.in.web.dto.response.OfficialLatestResponse;
import com.sneezecast.domainlayer.official.application.info.OfficialLatestInfo;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 공식 정보 Info → 응답 DTO. 값을 계산하지 않고 옮기기만 한다 — enum 은 coding-conventions §7 metadata 객체로, 적재 시각은 §2-2 대로 초 단위 UTC
 * {@link Instant} 로 바꾼다.
 *
 * <p>{@code synced_at} 은 batch 가 <b>JVM 기본 시간대의 지역 시각</b>으로 쓴다 (배포 환경은 {@code -Duser.timezone=Asia/Seoul}, {@code ReportPresenter}
 * 와 같은 전제). 같은 기준으로 읽어 순간으로 되돌린다.
 */
@Component
public class OfficialPresenter {

    private final ZoneId storageZone;

    public OfficialPresenter() {
        this(ZoneId.systemDefault());
    }

    /** 테스트가 JVM 시간대와 무관하게 변환을 고정할 때 쓴다. */
    OfficialPresenter(ZoneId storageZone) {
        this.storageZone = storageZone;
    }

    public OfficialLatestResponse toLatestResponse(OfficialLatestInfo latest) {
        return OfficialLatestResponse.builder()
            .programs(latest.programs().stream().map(this::toProgramItem).toList())
            .build();
    }

    private OfficialProgramItem toProgramItem(OfficialLatestInfo.Program program) {
        return OfficialProgramItem.builder()
            .program(program.program().toMetadata())
            .source(program.source().toMetadata())
            .sourceUrl(program.sourceUrl())
            .diseases(program.diseases().stream().map(this::toDiseaseItem).toList())
            .build();
    }

    private OfficialDiseaseItem toDiseaseItem(OfficialLatestInfo.Disease disease) {
        return OfficialDiseaseItem.builder()
            .diseaseKey(disease.diseaseKey())
            .diseaseName(disease.diseaseName())
            .diseaseGroup(disease.diseaseGroup())
            .metric(disease.metric().toMetadata())
            .regionLevel(disease.regionLevel().toMetadata())
            .regionCode(disease.regionCode())
            .regionName(disease.regionName())
            .periodYear(disease.periodYear())
            .periodWeek(disease.periodWeek())
            .periodStart(disease.periodStart())
            .periodEnd(disease.periodEnd())
            .syncedAt(toInstant(disease.syncedAt()))
            .values(disease.values().stream()
                .map(value -> OfficialValueItem.builder().ageGroup(value.ageGroup().toMetadata()).value(value.value()).build())
                .toList())
            .build();
    }

    private Instant toInstant(LocalDateTime storedAt) {
        return storedAt.atZone(storageZone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
