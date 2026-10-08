package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult;

/** surveillance 에 회원의 원시 보고를 지우라고 알린다 (architecture-guide §4, 받는 쪽은 멱등). */
public interface ReportPurgeCommandPort {

    /**
     * 실패를 예외로 올리지 않고 갈래로 돌려준다 — 스케줄러가 갈래마다 기록 · 중단을 정한다.
     *
     * @return 성공 · 거절 · 응답 없음 · 서킷 오픈 중 하나
     */
    ReportPurgeCallResult purgeReporter(long memberId);
}
