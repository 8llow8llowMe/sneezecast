package com.sneezecast.domainlayer.report.application.port.in;

/**
 * 서비스 간 보고 API. 지금은 auth 의 원시 보고 파기 요청(entity-design §1-5)만 받는다.
 */
public interface ReportInternalUseCase {

    /**
     * 회원의 원시 보고를 모든 주에 걸쳐 지운다. 지울 행이 없어도 성공이다 (멱등) — auth 가 완료될 때까지 같은 회원으로 여러 번 부른다.
     * 회원 ID 는 가명 키를 만드는 데만 쓴다.
     *
     * @param memberId auth 회원 ID (양수 — 형식은 컨트롤러가 먼저 거른다)
     */
    void purgeReporter(long memberId);
}
