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
     * <p><b>건강정보 동의 철회 전용이다.</b> 중복 판정이 사유를 보지 않으므로, 탈퇴(#154)가 이 메서드를 그대로 쓰면 철회 파기가 남아 있는 회원에게
     * {@code WITHDRAWAL} 행이 생기지 않는다. 탈퇴는 hard delete 판정이 사유에 기대므로 #154 에서 사유를 보는 판정을 따로 둔다.
     *
     * @return 새 요청을 남겼으면 true
     */
    public boolean requestIfAbsent(long memberId, PurgeReason reason, LocalDateTime requestedAt) {
        if (reportPurgeRequestRepositoryPort.existsIncompleteByMemberId(memberId)) {
            return false;
        }
        reportPurgeRequestRepositoryPort.save(ReportPurgeRequest.builder()
            .id(snowflakeIdGenerator.generateId())
            .memberId(memberId)
            .reason(reason)
            .requestedAt(requestedAt)
            .attemptCount(0)
            .build());
        return true;
    }
}
