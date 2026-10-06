package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;

public interface ReportPurgeRequestRepositoryPort {

    void save(ReportPurgeRequest request);

    /** 회원에게 완료되지 않은({@code completed_at is null}) 파기 요청이 하나라도 있는지. */
    boolean existsIncompleteByMemberId(long memberId);
}
