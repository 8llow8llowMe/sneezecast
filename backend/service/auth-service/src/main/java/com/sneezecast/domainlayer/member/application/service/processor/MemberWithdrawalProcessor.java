package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberNotificationSettingRepositoryPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRegionCleanupPort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.support.WithdrawnEmailHasher;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴의 DB 구간 (#154, entity-design §1-1). 회원 행 갱신 · 보고 파기 요청 · 회원 데이터 정리를 <b>한 트랜잭션</b>으로 묶는다 — 하나만 남으면 "탈퇴했는데
 * 보고 파기 요청이 없음"(보고가 52주 남는다)이나 "파기 요청은 있는데 회원은 그대로" 가 된다.
 *
 * <p>트랜잭션을 Facade 가 아니라 여기에 건다. 커밋 뒤에 모든 기기 로그아웃(Redis)이 이어지므로 Facade 에 걸면 커넥션을 잡은 채 원격 저장소를 기다린다
 * (architecture-guide §3-1 — 동의 철회와 같은 경계).
 */
@Service
@RequiredArgsConstructor
public class MemberWithdrawalProcessor {

    /** 탈퇴 회원의 닉네임 자리에 두는 고정 값(닉네임은 not null). 원래 닉네임을 남기지 않는다. */
    public static final String WITHDRAWN_NICKNAME = "탈퇴회원";

    private final MemberQueryProcessor memberQueryProcessor;
    private final MemberRepositoryPort memberRepositoryPort;
    private final WithdrawnEmailHasher withdrawnEmailHasher;
    private final ReportPurgeRequestProcessor reportPurgeRequestProcessor;
    private final MemberRegionCleanupPort memberRegionCleanupPort;
    private final MemberNotificationSettingRepositoryPort memberNotificationSettingRepositoryPort;

    /**
     * 회원 상태 확인(쓰기 잠금) → 회원 행 탈퇴 갱신 → {@code WITHDRAWAL} 파기 요청 → 관심 동네 · 알림 설정 삭제. 돌아온 시점이 커밋 뒤다.
     *
     * <ul>
     *   <li><b>회원 행을 쓰기 잠금으로 읽고 상태를 본다.</b> 같은 회원의 탈퇴 두 건이 겹치면 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다렸다가 WITHDRAWN 을
     *       보고 {@code MEMBER_002} 로 끝난다 — 회원 행 갱신 · 파기 요청이 두 번 생기지 않는다.</li>
     *   <li>회원 행: 이메일 원문 → 다이제스트(회원 ID 를 섞은 HMAC — {@link WithdrawnEmailHasher}), 비밀번호 · 소셜 제공자 · 프로필 이미지 → null, 닉네임 →
     *       {@link #WITHDRAWN_NICKNAME}, 상태 WITHDRAWN, 탈퇴 시각. 원문 이메일이 사라져 같은 이메일로 바로 다시 가입할 수 있다. 업로드한 프로필 이미지
     *       오브젝트는 여기서 지우지 않는다(트랜잭션 안에서 외부 저장소를 기다리지 않는다 — 고아 이미지 정리 #112 가 맡는다).</li>
     *   <li>파기 요청은 사유를 보는 판정이라 철회 파기가 미완료로 남아 있어도 {@code WITHDRAWAL} 행이 생긴다. 요청 시각은 탈퇴 시각과 같다.</li>
     *   <li>관심 동네 · 알림 설정은 지금 지운다(탈퇴한 회원에게 쓸 데가 없다). 동의 이력 · 내 동네는 남기고 회원 파기(#244) 때 지운다 — 동의 이력은 파기 전까지의
     *       증빙이다.</li>
     * </ul>
     *
     * @throws MemberException 회원 행이 없으면 {@code MEMBER_NOT_FOUND}(404), 이미 탈퇴 {@code WITHDRAWN_MEMBER}(403) · 정지 {@code SUSPENDED_MEMBER}(403)
     */
    @Transactional
    public void withdraw(long memberId) {
        Member member = memberQueryProcessor.getActiveMemberForUpdate(memberId);
        LocalDateTime withdrawnAt = LocalDateTime.now();
        memberRepositoryPort.withdraw(memberId, withdrawnEmailHasher.hash(memberId, member.email()), WITHDRAWN_NICKNAME, withdrawnAt)
            .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
        reportPurgeRequestProcessor.requestWithdrawalIfAbsent(memberId, withdrawnAt);
        memberRegionCleanupPort.deleteInterestRegions(memberId);
        memberNotificationSettingRepositoryPort.deleteByMemberId(memberId);
    }
}
