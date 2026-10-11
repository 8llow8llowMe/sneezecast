package com.sneezecast.domainlayer.advisory.application.service;

import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorDetailResponse;
import com.sneezecast.domainlayer.advisory.adapter.in.web.dto.response.AdvisoryOperatorResponse;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryDraftCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryEditCommand;
import com.sneezecast.domainlayer.advisory.application.command.AdvisoryRetractCommand;
import com.sneezecast.domainlayer.advisory.application.port.in.AdvisoryOperatorWebUseCase;
import com.sneezecast.domainlayer.advisory.application.service.presenter.AdvisoryOperatorPresenter;
import com.sneezecast.domainlayer.advisory.application.service.processor.AdvisoryCommandProcessor;
import com.sneezecast.domainlayer.advisory.application.service.processor.AdvisoryQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 안내 처리 오케스트레이션. 외부 I/O 도 재시도도 없어 트랜잭션을 여기에 건다 (architecture-guide §3-1) — 안내 갱신과 이력 한 행이 함께
 * 커밋된다. 낙관적 락 충돌은 저장 어댑터가 그 자리에서 {@code ADVISORY_006} 으로 바꿔 던지고, 다시 시도하지 않는다(화면이 다시 읽는다). 저장소가 던진
 * {@code ADVISORY_001} · {@code ADVISORY_006} 은 잡지 않고 그대로 올린다 — 트랜잭션이 이미 rollback-only 라 잡고 이어 가면 커밋에서
 * {@code UnexpectedRollbackException} 이 난다.
 *
 * <p>AI 초안(DRAFT → AI_DRAFTED)은 여기에 두지 않는다 — 외부 호출이 끼므로 트랜잭션을 Processor 단위로 좁힌 별도 경로가 된다(AI 이슈).
 */
@Service
@RequiredArgsConstructor
public class AdvisoryOperatorWebFacade implements AdvisoryOperatorWebUseCase {

    private final AdvisoryCommandProcessor advisoryCommandProcessor;
    private final AdvisoryQueryProcessor advisoryQueryProcessor;
    private final AdvisoryOperatorPresenter advisoryOperatorPresenter;

    @Override
    @Transactional
    public AdvisoryOperatorResponse createDraft(long operatorId, AdvisoryDraftCommand command) {
        return advisoryOperatorPresenter.toResponse(advisoryCommandProcessor.createDraft(operatorId, command));
    }

    @Override
    @Transactional
    public AdvisoryOperatorResponse edit(long operatorId, long advisoryId, AdvisoryEditCommand command) {
        return advisoryOperatorPresenter.toResponse(advisoryCommandProcessor.edit(operatorId, advisoryId, command));
    }

    @Override
    @Transactional
    public AdvisoryOperatorResponse approve(long operatorId, long advisoryId, long expectedVersion) {
        return advisoryOperatorPresenter.toResponse(advisoryCommandProcessor.approve(operatorId, advisoryId, expectedVersion));
    }

    @Override
    @Transactional
    public AdvisoryOperatorResponse publish(long operatorId, long advisoryId, long expectedVersion) {
        return advisoryOperatorPresenter.toResponse(advisoryCommandProcessor.publish(operatorId, advisoryId, expectedVersion));
    }

    @Override
    @Transactional
    public AdvisoryOperatorResponse retract(long operatorId, long advisoryId, AdvisoryRetractCommand command) {
        return advisoryOperatorPresenter.toResponse(advisoryCommandProcessor.retract(operatorId, advisoryId, command));
    }

    @Override
    @Transactional(readOnly = true)
    public AdvisoryOperatorDetailResponse getDetail(long advisoryId) {
        return advisoryOperatorPresenter.toDetailResponse(advisoryQueryProcessor.getDetail(advisoryId));
    }
}
