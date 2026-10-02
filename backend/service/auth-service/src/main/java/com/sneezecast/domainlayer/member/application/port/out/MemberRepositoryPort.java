package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.domain.model.Member;
import java.util.Optional;

public interface MemberRepositoryPort {

    /**
     * 회원을 저장하고 즉시 반영한다.
     *
     * @throws com.sneezecast.domainlayer.member.application.exception.MemberException 이메일이 이미 있으면
     *         {@code EXIST_MEMBER_EMAIL} — 사전 조회를 통과한 동시 가입을 DB unique 제약이 막은 경우다
     */
    Member save(Member member);

    /** 상태와 무관하게 이메일을 쓰는 회원 행이 있는지 본다 (탈퇴 회원도 파기 전까지는 이메일을 점유한다). */
    boolean existsByEmail(String email);

    /** 정규화한 이메일로 회원을 찾는다. 상태와 무관하다 — 상태 판정은 호출자가 한다. */
    Optional<Member> findByEmail(String email);

    Optional<Member> findById(long memberId);
}
