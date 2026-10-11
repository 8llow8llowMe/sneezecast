package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.info.MemberMyInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberReportScopePort;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.storage.client.ObjectStorageClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 회원 조회 · 상태 판정. 트랜잭션은 호출자(Facade)가 정한다.
 */
@Service
@RequiredArgsConstructor
public class MemberQueryProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final MemberConsentProcessor memberConsentProcessor;
    private final MemberReportScopePort memberReportScopePort;
    private final ObjectStorageClient objectStorageClient;

    /**
     * 인증 주체의 회원을 돌려준다. 행이 없으면(파기됨) {@code MEMBER_NOT_FOUND}, 탈퇴 · 정지면 상태별 코드(MEMBER_002 · 003)다 — 남은 access(15분
     * 이하)로 들어온 요청이 내 정보를 바꾸지 못하게 한다.
     */
    public Member getActiveMember(long memberId) {
        return requireActive(memberRepositoryPort.findById(memberId));
    }

    /**
     * {@link #getActiveMember} 와 같은 판정을 회원 행을 <b>쓰기 잠금으로</b> 읽어서 한다 — 탈퇴처럼 상태를 바꾸는 쓰기가 같은 회원의 겹친 요청과 차례로
     * 처리되게 한다(뒤 요청은 앞 트랜잭션이 끝난 뒤의 상태를 본다). 호출자 트랜잭션이 있어야 한다.
     */
    public Member getActiveMemberForUpdate(long memberId) {
        return requireActive(memberRepositoryPort.findByIdForUpdate(memberId));
    }

    /** 회원 행이 없으면 {@code MEMBER_NOT_FOUND}, 탈퇴 · 정지면 상태별 코드. 같은 패키지의 쓰기 처리기({@link MemberCommandProcessor})도 같은 판정을 쓴다. */
    static Member requireActive(Optional<Member> found) {
        Member member = found.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
        switch (member.status()) {
            case WITHDRAWN -> throw new MemberException(MemberErrorCode.WITHDRAWN_MEMBER);
            case SUSPENDED -> throw new MemberException(MemberErrorCode.SUSPENDED_MEMBER);
            case ACTIVE -> {
            }
        }
        return member;
    }

    /**
     * 내 정보. {@code pendingConsents} · {@code reportWritable} 은 로그인 · 재발급과 <b>같은 계산</b>(동의 이력 → auth 의 scope 정책, {@link MemberReportScopePort})이라 화면이
     * 토큰 응답과 어긋난 상태를 보지 않는다. 동의가 바뀐 직후에는 이 값이 지금 access token 의 scope 보다 먼저 바뀔 수 있다(access 는 재발급 때 따라온다).
     */
    public MemberMyInfo getMyInfo(Member member) {
        MemberConsentStatusInfo consentStatus = memberConsentProcessor.currentStatus(member.id());
        return MemberMyInfo.builder()
            .memberId(member.id())
            .email(member.email())
            .nickname(member.nickname())
            .profileImageUrl(profileImageUrl(member))
            .provider(member.provider())
            .hasPassword(member.password() != null)
            .role(member.role())
            .pendingConsents(consentStatus.pendingRequiredConsents())
            .reportWritable(memberReportScopePort.isReportWritable(consentStatus))
            .build();
    }

    /**
     * 표시할 프로필 이미지 주소 — 업로드한 오브젝트 키가 있으면 그 공개 URL(키만 저장하고 응답 때 조립한다), 없으면 소셜 제공자가 준 URL, 둘 다 없으면 null.
     * 공개 URL 조립은 문자열 결합이라 원격 I/O 가 없다.
     */
    private String profileImageUrl(Member member) {
        if (member.profileImageKey() != null) {
            return objectStorageClient.toPublicUrl(member.profileImageKey());
        }
        return member.profileImageUrl();
    }
}
