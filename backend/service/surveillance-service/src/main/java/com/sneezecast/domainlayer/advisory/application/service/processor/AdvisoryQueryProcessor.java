package com.sneezecast.domainlayer.advisory.application.service.processor;

import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryErrorCode;
import com.sneezecast.domainlayer.advisory.application.exception.AdvisoryException;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryDetailInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryHistoryInfo;
import com.sneezecast.domainlayer.advisory.application.info.AdvisoryInfo;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryHistoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 운영자용 안내 조회. 트랜잭션은 호출자(WebFacade, 읽기 전용)가 건다 — 안내와 이력을 한 스냅샷에서 읽는다.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryQueryProcessor {

    private final AdvisoryRepositoryPort advisoryRepositoryPort;
    private final AdvisoryHistoryRepositoryPort advisoryHistoryRepositoryPort;

    /**
     * 안내 한 건과 전 이력(오래된 것부터).
     *
     * @throws AdvisoryException 없는 안내 {@code ADVISORY_001}(404)
     */
    public AdvisoryDetailInfo getDetail(long advisoryId) {
        AdvisoryInfo advisory = advisoryRepositoryPort.findById(advisoryId)
            .map(AdvisoryInfo::from)
            .orElseThrow(() -> new AdvisoryException(AdvisoryErrorCode.ADVISORY_NOT_FOUND));
        return new AdvisoryDetailInfo(advisory, advisoryHistoryRepositoryPort.findAllByAdvisoryId(advisoryId).stream().map(AdvisoryHistoryInfo::from).toList());
    }
}
