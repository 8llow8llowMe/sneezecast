package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * surveillance 원시 보고 파기 요청을 남기고 미완료 여부를 판정한다 (entity-design §1-5). 요청을 실제로 보내 완료 처리하는 스케줄러는 #155 다.
 *
 * <p>트랜잭션을 스스로 열지 않는다 — 요청 행은 그 원인(동의 철회 · 탈퇴)과 같은 트랜잭션에 남아야 한다.
 */
@Service
@RequiredArgsConstructor
public class ReportPurgeRequestProcessor {

    private final ReportPurgeRequestRepositoryPort reportPurgeRequestRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** 완료되지 않은 파기 요청이 있는지. 있으면 {@code report:write} 를 싣지 않는다({@code ReportScopePolicy}). */
    public boolean hasIncompletePurge(long memberId) {
        return reportPurgeRequestRepositoryPort.existsIncompleteByMemberId(memberId);
    }

    /**
     * 미완료 요청이 없을 때만 새 요청을 남긴다. 이미 있으면 그 요청이 아직 돌지 않았거나 재시도 중이라, 끝날 때 그 시각까지의 보고가 함께 지워진다
     * — 둘째 행은 같은 파기를 한 번 더 부를 뿐이다.
     *
     * <p><b>건강정보 동의 철회 전용이다.</b> 중복 판정이 사유를 보지 않으므로, 탈퇴가 이 메서드를 그대로 쓰면 철회 파기가 남아 있는 회원에게
     * {@code WITHDRAWAL} 행이 생기지 않는다. 탈퇴는 {@link #requestWithdrawalIfAbsent} 를 쓴다.
     *
     * @return 새 요청을 남겼으면 true
     */
    public boolean requestIfAbsent(long memberId, PurgeReason reason, LocalDateTime requestedAt) {
        if (reportPurgeRequestRepositoryPort.existsIncompleteByMemberId(memberId)) {
            return false;
        }
        save(memberId, reason, requestedAt);
        return true;
    }

    /**
     * 탈퇴 파기 요청({@code WITHDRAWAL})을 남긴다. 중복 판정은 <b>같은 사유의 미완료 요청만</b> 본다 — 건강정보 동의 철회 요청이 미완료로 남아 있어도
     * {@code WITHDRAWAL} 행은 반드시 생긴다. 탈퇴 사실과 그 시각(완료 경계의 기준)이 파기 증빙으로 남아야 하고, 회원 파기(#244)가 탈퇴 요청의 완료를
     * 보고 판정하기 때문이다.
     *
     * <p>같은 회원의 탈퇴는 회원 행 잠금으로 차례로 처리되고 둘째는 상태 확인에서 막히므로 이 판정이 실제로 걸리는 일은 없다. 그래도 중복 행이 생기지 않는
     * 마지막 보루로 둔다.
     *
     * @return 새 요청을 남겼으면 true
     */
    public boolean requestWithdrawalIfAbsent(long memberId, LocalDateTime requestedAt) {
        if (reportPurgeRequestRepositoryPort.existsIncompleteByMemberIdAndReason(memberId, PurgeReason.WITHDRAWAL)) {
            return false;
        }
        save(memberId, PurgeReason.WITHDRAWAL, requestedAt);
        return true;
    }

    private void save(long memberId, PurgeReason reason, LocalDateTime requestedAt) {
        reportPurgeRequestRepositoryPort.save(ReportPurgeRequest.builder()
            .id(snowflakeIdGenerator.generateId())
            .memberId(memberId)
            .reason(reason)
            .requestedAt(requestedAt)
            .attemptCount(0)
            .build());
    }
}
