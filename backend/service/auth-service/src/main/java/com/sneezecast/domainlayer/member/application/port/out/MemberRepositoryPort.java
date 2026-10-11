package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.time.LocalDateTime;
import java.util.Optional;

public interface MemberRepositoryPort {

    /**
     * 회원을 저장하고 즉시 반영한다.
     *
     * @throws com.sneezecast.domainlayer.member.application.exception.MemberException 이메일이 이미 있으면
     *         {@code EXIST_MEMBER_EMAIL} — 사전 조회를 통과한 동시 가입을 DB unique 제약이 막은 경우다
     */
    Member save(Member member);

    /**
     * 상태와 무관하게 이메일을 쓰는 회원 행이 있는지 본다. 탈퇴한 회원은 이메일이 다이제스트로 바뀌어 원문으로는 걸리지 않는다 — 탈퇴 즉시 같은 이메일로
     * 다시 가입할 수 있다(entity-design §1-1).
     */
    boolean existsByEmail(String email);

    /** 정규화한 이메일로 회원을 찾는다. 상태와 무관하다 — 상태 판정은 호출자가 한다. 탈퇴한 회원은 원문 이메일로 찾을 수 없다(다이제스트). */
    Optional<Member> findByEmail(String email);

    Optional<Member> findById(long memberId);

    /**
     * 회원 행을 <b>쓰기 잠금으로</b> 읽고(SELECT ... FOR UPDATE) 커밋된 최신 값을 돌려준다 — 같은 트랜잭션에서 앞서 읽은 값이 있어도 덮어쓴다. 회원 행을 바꾸는
     * 모든 쓰기(탈퇴 · 닉네임 · 비밀번호 · 소셜 연결)가 먼저 이것으로 상태를 다시 본다: 겹친 쓰기는 앞 트랜잭션이 끝날 때까지 기다렸다가 바뀐 행(WITHDRAWN)을
     * 보고, 탈퇴 뒤에 낡은 값으로 행을 되살리지 못한다. <b>호출자 트랜잭션이 있어야 한다.</b>
     *
     * <p>부르기 전에 같은 회원 엔티티를 고치지 않는다 — 최신 행으로 다시 읽으면서(refresh) flush 하지 않은 변경을 조용히 버린다. 행이 없어 empty 를
     * 받으면 그 트랜잭션은 이미 롤백 전용이므로 예외로 끝낸다(다른 일을 하고 커밋하면 UnexpectedRollbackException).
     */
    Optional<Member> findByIdForUpdate(long memberId);

    /**
     * 기존 회원 행을 탈퇴 상태로 바꾼다 — 이메일은 다이제스트, 비밀번호 · 소셜 제공자 · 프로필 이미지는 비우고, 닉네임은 마스킹 값, 상태 WITHDRAWN, 탈퇴
     * 시각. 엔티티를 조회해 변경 감지로 고친다 — <b>호출자 트랜잭션이 있어야 한다.</b> 상태 확인은 호출자가 같은 트랜잭션에서 먼저 한다.
     *
     * @return 바꾼 회원. 행이 없으면 empty
     */
    Optional<Member> withdraw(long memberId, String emailDigest, String maskedNickname, LocalDateTime withdrawnAt);

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

    /**
     * 기존 회원 행에 소셜 로그인 제공자를 연결한다(비밀번호는 그대로). 엔티티를 조회해 변경 감지로 고친다 — <b>호출자 트랜잭션이 있어야 한다.</b>
     *
     * @return 바꾼 회원. 행이 없으면 empty
     */
    Optional<Member> updateProvider(long memberId, OAuthProvider provider);
}
