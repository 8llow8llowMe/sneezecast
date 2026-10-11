package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 행 수정의 DB 구간. 엔티티를 조회해 변경 감지로 고친다 (coding-conventions §8-1 — 매핑한 엔티티를 save 하면 PK 위반).
 *
 * <p>트랜잭션을 여기에 건다. 비밀번호 변경 · 재설정은 앞뒤에 BCrypt 와 세션 저장소(Redis) 왕복이 있어 Facade 에 걸면 커넥션을 잡은 채 원격 저장소를
 * 기다린다 (architecture-guide §3-1). 이미 트랜잭션 안(내 정보 수정 Facade)이면 거기에 합류한다.
 *
 * <p><b>모든 수정은 먼저 회원 행을 쓰기 잠금으로 다시 읽고 ACTIVE 인지 본다</b>({@link #lockActiveMember}). 변경 감지는 전 컬럼을 UPDATE 하므로, 잠금 없이
 * 읽은 뒤 그사이 탈퇴가 커밋되면 낡은 값(ACTIVE · 원문 이메일 · 비밀번호)으로 탈퇴를 되살린다. 탈퇴가 먼저 커밋됐는데 앞서 확인을 통과한 비밀번호 변경 ·
 * 재설정이 WITHDRAWN 행에 해시를 다시 쓰는 일도 막는다. 잠그는 행은 회원 행 하나이고 다른 행 잠금보다 먼저 잡으므로 잠금 순서가 엇갈리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MemberCommandProcessor {

    private final MemberRepositoryPort memberRepositoryPort;

    /**
     * @param nickname 앞뒤 공백을 걷은 닉네임
     * @throws MemberException 회원 없음 {@code MEMBER_NOT_FOUND} · 탈퇴 {@code WITHDRAWN_MEMBER} · 정지 {@code SUSPENDED_MEMBER}
     */
    @Transactional
    public Member changeNickname(long memberId, String nickname) {
        lockActiveMember(memberId);
        return memberRepositoryPort.updateNickname(memberId, nickname).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * @param encodedPassword 트랜잭션 밖에서 계산한 BCrypt 해시
     * @throws MemberException 회원 없음 · 탈퇴 · 정지 — 회원 API 는 그대로 내고, 재설정은 호출자가 만료와 같은 코드로 바꾼다
     */
    @Transactional
    public void changePassword(long memberId, String encodedPassword) {
        lockActiveMember(memberId);
        memberRepositoryPort.updatePassword(memberId, encodedPassword).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * 소셜 로그인 제공자를 연결한다(비밀번호는 그대로). 연결해도 되는 계정인지(상태 · 이미 연결됨)는 호출자가 같은 트랜잭션 안에서 잠금 읽기로 먼저 확인한다 —
     * 이 처리기는 auth 의 오류 코드를 모른다(member → auth 의존 금지). 여기서도 같은 잠금 판정을 한 번 더 한다(같은 트랜잭션이라 잠금은 이미 잡혀 있다).
     */
    @Transactional
    public Member changeProvider(long memberId, OAuthProvider provider) {
        lockActiveMember(memberId);
        return memberRepositoryPort.updateProvider(memberId, provider).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * 프로필 이미지를 업로드한 오브젝트 키로 바꾸고 소셜 URL 은 비운다. 업로드(원격 I/O)는 호출자가 트랜잭션 밖에서 먼저 끝낸다.
     *
     * <p>이전 key 는 잠금 읽기로 본 커밋된 값이다 — 같은 회원의 업로드가 겹쳐도 뒤 요청이 앞 요청의 key 를 이전 key 로 받아 지우므로 고아가 남지 않는다.
     *
     * @return 바꾸기 전에 쓰던 업로드 오브젝트 키. 없었으면(소셜 URL 이거나 이미지 없음) empty — 호출자가 커밋 뒤에 지운다
     * @throws MemberException 회원 없음 {@code MEMBER_NOT_FOUND} · 탈퇴 {@code WITHDRAWN_MEMBER} · 정지 {@code SUSPENDED_MEMBER}
     */
    @Transactional
    public Optional<String> changeProfileImage(long memberId, String profileImageKey) {
        Member locked = lockActiveMember(memberId);
        memberRepositoryPort.updateProfileImage(memberId, profileImageKey).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
        return Optional.ofNullable(locked.profileImageKey());
    }

    /**
     * 프로필 이미지(업로드 key · 소셜 URL)를 비운다. 이미지가 없어도 같은 결과로 끝난다(멱등).
     *
     * @return 지우기 전에 쓰던 업로드 오브젝트 키. 없었으면 empty — 호출자가 커밋 뒤에 지운다
     * @throws MemberException 회원 없음 · 탈퇴 · 정지
     */
    @Transactional
    public Optional<String> removeProfileImage(long memberId) {
        return changeProfileImage(memberId, null);
    }

    /** 회원 행을 쓰기 잠금으로 다시 읽어(커밋된 최신 값) ACTIVE 인지 본다 — {@link MemberQueryProcessor#getActiveMember} 와 같은 판정이다. */
    private Member lockActiveMember(long memberId) {
        return MemberQueryProcessor.requireActive(memberRepositoryPort.findByIdForUpdate(memberId));
    }
}
