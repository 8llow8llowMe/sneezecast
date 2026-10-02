package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 행 수정의 DB 구간. 엔티티를 조회해 변경 감지로 고친다 (coding-conventions §8-1 — 매핑한 엔티티를 save 하면 PK 위반).
 *
 * <p>트랜잭션을 여기에 건다. 비밀번호 변경 · 재설정은 앞뒤에 BCrypt 와 세션 저장소(Redis) 왕복이 있어 Facade 에 걸면 커넥션을 잡은 채 원격 저장소를
 * 기다린다 (architecture-guide §3-1). 이미 트랜잭션 안(내 정보 수정 Facade)이면 거기에 합류한다.
 */
@Service
@RequiredArgsConstructor
public class MemberCommandProcessor {

    private final MemberRepositoryPort memberRepositoryPort;

    /** @param nickname 앞뒤 공백을 걷은 닉네임 */
    @Transactional
    public Member changeNickname(long memberId, String nickname) {
        return memberRepositoryPort.updateNickname(memberId, nickname).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /** @param encodedPassword 트랜잭션 밖에서 계산한 BCrypt 해시 */
    @Transactional
    public void changePassword(long memberId, String encodedPassword) {
        memberRepositoryPort.updatePassword(memberId, encodedPassword).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * 소셜 로그인 제공자를 연결한다(비밀번호는 그대로). 연결해도 되는 계정인지(상태 · 이미 연결됨)는 호출자가 같은 트랜잭션 안에서 먼저 확인한다 — 이 처리기는
     * auth 의 오류 코드를 모른다(member → auth 의존 금지).
     */
    @Transactional
    public Member changeProvider(long memberId, OAuthProvider provider) {
        return memberRepositoryPort.updateProvider(memberId, provider).orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
