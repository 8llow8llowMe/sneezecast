package com.sneezecast.domainlayer.member.application.service;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.in.MemberConsentWithdrawResult;
import com.sneezecast.domainlayer.member.application.port.in.MemberWebUseCase;
import com.sneezecast.domainlayer.member.application.port.out.MemberReportScopePort;
import com.sneezecast.domainlayer.member.application.port.out.MemberSessionRevokePort;
import com.sneezecast.domainlayer.member.application.service.presenter.MemberPresenter;
import com.sneezecast.domainlayer.member.application.service.processor.MemberCommandProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberConsentProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberPasswordProcessor;
import com.sneezecast.domainlayer.member.application.service.processor.MemberQueryProcessor;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.domainlayer.member.domain.model.Member;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보 · 비밀번호 변경 · 동의(재동의 · 건강정보 동의 · 철회) 오케스트레이션. 카카오로만 로그인하는 회원은 비밀번호가 없고 따로 정하지도 않는다(#61 — 설정 API 를 두지 않는다).
 *
 * <p>내 정보 조회 · 수정은 DB 만 쓰므로 Facade 에 트랜잭션을 건다. 비밀번호 변경은 BCrypt(CPU)와 세션 저장소(Redis) 왕복이 섞여 Facade 에
 * 걸지 않고, DB 쓰기만 {@link MemberCommandProcessor#changePassword} 로 좁힌다 (architecture-guide §3-1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWebFacade implements MemberWebUseCase {

    private final MemberQueryProcessor memberQueryProcessor;
    private final MemberCommandProcessor memberCommandProcessor;
    private final MemberPasswordProcessor memberPasswordProcessor;
    private final MemberConsentProcessor memberConsentProcessor;
    private final MemberReportScopePort memberReportScopePort;
    private final MemberSessionRevokePort memberSessionRevokePort;
    private final PasswordEncoder passwordEncoder;
    private final MemberPresenter memberPresenter;

    @Override
    @Transactional(readOnly = true)
    public MemberMyInfoResponse getMyInfo(long memberId) {
        Member member = memberQueryProcessor.getActiveMember(memberId);
        return memberPresenter.toMyInfoResponse(memberQueryProcessor.getMyInfo(member));
    }

    /** 상태 확인 → 조회한 엔티티의 닉네임을 바꿔 변경 감지로 저장 → 바뀐 내 정보. */
    @Override
    @Transactional
    public MemberMyInfoResponse updateMyInfo(long memberId, String nickname) {
        memberQueryProcessor.getActiveMember(memberId);
        Member updated = memberCommandProcessor.changeNickname(memberId, nickname.strip());
        return memberPresenter.toMyInfoResponse(memberQueryProcessor.getMyInfo(updated));
    }

    @Override
    public void changePassword(long memberId, String sessionId, String currentPassword, String newPassword) {
        Member member = memberQueryProcessor.getActiveMember(memberId);
        memberPasswordProcessor.requirePasswordSet(member);
        memberPasswordProcessor.verifyCurrentPassword(member, currentPassword);
        replacePassword(memberId, sessionId, newPassword);
    }

    /**
     * DB 만 쓰므로 Facade 에 트랜잭션을 건다 — 상태 확인 · 동의 행 추가 · 상태 응답이 한 트랜잭션이다. access 의 scope 는 그대로이고 재발급 때
     * 따라온다(토큰은 발급 때 계산한다).
     */
    @Override
    @Transactional
    public MemberConsentStatusResponse agreeConsent(long memberId, ConsentType type, String documentVersion) {
        memberQueryProcessor.getActiveMember(memberId);
        memberConsentProcessor.agree(memberId, type, documentVersion);
        return consentStatus(memberId);
    }

    /**
     * 철회 + 파기 요청(DB 트랜잭션, 커밋) → 모든 기기 로그아웃(Redis) → 상태. Redis 를 기다리는 동안 커넥션을 잡지 않게 Facade 에는 트랜잭션을 걸지 않는다
     * ({@link MemberConsentProcessor#withdraw} 가 돌아온 시점이 커밋 뒤다).
     *
     * <ul>
     *   <li>철회할 동의가 없으면(이미 철회 · 동의한 적 없음) 아무것도 하지 않는다(멱등) — 세션 · 쿠키도 그대로다.</li>
     *   <li><b>로그아웃이 실패해도(세션 저장소 장애) 로그만 남기고 성공으로 끝낸다.</b> 철회 · 파기 요청은 이미 커밋됐다. 남은 refresh 로 재발급하면
     *       scope 가 다시 계산돼 {@code report:write} 가 빠지고, 그 전에 남은 access(최대 15분)로 들어온 보고는 파기 요청의 2차 호출(#155)이
     *       지운다(entity-design §1-5). 503 으로 돌려주면 화면은 철회가 실패한 것으로 보지만 실제로는 끝났다.</li>
     * </ul>
     */
    @Override
    public MemberConsentWithdrawResult withdrawConsent(long memberId, ConsentType type, String accessTokenId, Instant accessExpiresAt) {
        memberQueryProcessor.getActiveMember(memberId);
        boolean withdrawn = memberConsentProcessor.withdraw(memberId, type);
        if (withdrawn) {
            try {
                memberSessionRevokePort.revokeAllSessions(memberId, accessTokenId, accessExpiresAt);
            } catch (MemberException exception) {
                log.error("post-withdrawal session revoke failed, tolerated memberId={} code={}", memberId, exception.getErrorCode().getCode());
            }
        }
        return new MemberConsentWithdrawResult(consentStatus(memberId), withdrawn);
    }

    private MemberConsentStatusResponse consentStatus(long memberId) {
        MemberConsentStatusInfo status = memberConsentProcessor.currentStatus(memberId);
        return memberPresenter.toConsentStatusResponse(status, memberReportScopePort.isReportWritable(status));
    }

    /**
     * 새 비밀번호 해시(트랜잭션 밖) → 다른 기기 세션 폐기(Redis) → 비밀번호 저장(DB 트랜잭션, 커밋) → 같은 범위로 한 번 더 폐기.
     *
     * <ul>
     *   <li><b>세션을 먼저 끊는다.</b> 비밀번호를 먼저 바꾸고 세션 폐기가 실패하면, 비밀번호가 새어 바꾼 경우에도 공격자의 기기가 계속 로그인해 있다.
     *       폐기가 실패하면 {@code SESSION_REVOKE_UNAVAILABLE}(503)이고 비밀번호는 그대로라 사용자가 다시 시도하면 된다.</li>
     *   <li>폐기 뒤 저장이 실패하면 다른 기기만 로그아웃된 채 비밀번호는 그대로다 — 안전한 쪽의 실패라 되돌리지 않는다.</li>
     *   <li><b>커밋 뒤에 한 번 더 끊는다.</b> 1차 폐기와 커밋 사이에 옛 비밀번호로 BCrypt 를 통과한 로그인이 새 세션을 저장하면 그 세션이 남는다.
     *       이 Facade 는 트랜잭션을 열지 않으므로 {@link MemberCommandProcessor#changePassword} 가 돌아온 시점이 커밋 뒤다. 2차 폐기는 이미 바뀐
     *       비밀번호를 되돌릴 수 없으니 실패해도 로그만 남기고 성공으로 끝낸다(남는 위험은 그 틈에 생긴 세션 하나).</li>
     *   <li>지금 기기(access 의 sid)는 남긴다. sid 가 없는 토큰이면 무엇을 남길지 몰라 전부 끊는다.</li>
     * </ul>
     */
    private void replacePassword(long memberId, String sessionId, String newPassword) {
        String encodedPassword = passwordEncoder.encode(newPassword);
        memberSessionRevokePort.revokeOtherSessions(memberId, sessionId);
        memberCommandProcessor.changePassword(memberId, encodedPassword);
        try {
            memberSessionRevokePort.revokeOtherSessions(memberId, sessionId);
        } catch (MemberException exception) {
            log.error("post-commit session revoke failed, tolerated memberId={} code={}", memberId, exception.getErrorCode().getCode());
        }
    }
}
