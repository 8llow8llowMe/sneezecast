package com.sneezecast.domainlayer.auth.application.service.support;

import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.security.common.constant.SecurityScope;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * access token 에 어떤 scope 를 싣는지 정하는 단일 지점. 발급 <b>정책</b>은 auth 가 소유한다(security-core 는 claim 규약만 안다).
 *
 * <p>로그인 · 재발급마다 동의 상태를 다시 읽어 계산한다 — 동의가 바뀌면 늦어도 access 만료(15분 이하) 안에 scope 에 반영된다.
 */
@Component
public class ReportScopePolicy {

    /**
     * {@code report:write} 는 필수 동의가 모두 현재 버전으로 유효하고(재동의 대기 없음), 건강정보 동의가 유효하고, <b>미완료 보고 파기 요청이 없을 때만</b>
     * 싣는다.
     *
     * <p>파기 조건이 있는 이유 — 파기는 철회 시각 + access TTL 이후의 2차 호출까지 끝나야 완료다(entity-design §1-5). 철회 직후 재동의한 회원이 그 사이에
     * 보고하면, 아직 남은 파기 호출이 철회 전 보고와 함께 <b>재동의 뒤의 새 보고까지 지운다</b>(surveillance 는 회원 단위로 지운다). 그래서 철회 뒤
     * 재동의해도 파기가 끝나기 전에는 보고할 수 없다. 파기를 실행 · 완료 처리하는 재시도 스케줄러는 #155 라, 그 전까지는 철회한 회원이 다시 동의해도
     * 보고할 수 없다 — 2차 파기가 새 보고를 지우는 쪽보다 보고를 잠시 막는 쪽을 택했다.
     *
     * <p>로그인 · 재발급 · 내 정보 · 동의 응답이 모두 이 판정을 쓴다(입력은 {@code MemberConsentProcessor.currentStatus}).
     */
    public Set<String> scopesFor(MemberConsentStatusInfo consentStatus) {
        if (consentStatus.pendingRequiredConsents().isEmpty() && consentStatus.healthInfoAgreed() && !consentStatus.purgePending()) {
            return Set.of(SecurityScope.REPORT_WRITE);
        }
        return Set.of();
    }
}
