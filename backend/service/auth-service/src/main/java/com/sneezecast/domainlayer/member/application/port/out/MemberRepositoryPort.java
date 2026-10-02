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

    /**
     * 기존 회원 행의 닉네임을 바꾼다. 엔티티를 조회해 변경 감지로 고친다(coding-conventions §8-1) — <b>호출자 트랜잭션이 있어야 한다.</b>
     *
     * @return 바꾼 회원. 행이 없으면 empty
     */
    Optional<Member> updateNickname(long memberId, String nickname);

    /**
     * 기존 회원 행의 비밀번호 해시를 바꾼다. 엔티티를 조회해 변경 감지로 고친다 — <b>호출자 트랜잭션이 있어야 한다.</b>
     *
     * @param encodedPassword 트랜잭션 밖에서 계산한 BCrypt 해시
     * @return 바꾼 회원. 행이 없으면 empty
     */
    Optional<Member> updatePassword(long memberId, String encodedPassword);
}
