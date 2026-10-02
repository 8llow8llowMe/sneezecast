package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.domain.model.MemberConsent;
import java.util.List;

public interface MemberConsentRepositoryPort {

    /**
     * 동의 이력을 한 번에 저장한다. 가입 한 건이 항상 여러 항목을 함께 남기므로 단건 save 를 반복 호출하지 않도록 계약 자체를 벌크로 둔다.
     */
    void saveAll(List<MemberConsent> consents);

    /**
     * 회원의 동의 이력 전부(항목 · 철회 여부 무관). 항목별 최신 행 판정은 호출자({@code MemberConsentProcessor})가 한다 — 회원당 행 수가
     * 항목 수 × 재동의 횟수 정도라 한 번에 읽고 메모리에서 고르는 편이 항목별 쿼리를 여러 번 내는 것보다 낫다.
     */
    List<MemberConsent> findAllByMemberId(long memberId);
}
