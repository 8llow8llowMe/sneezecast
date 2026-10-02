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
     * {@code report:write} 는 필수 동의가 모두 현재 버전으로 유효하고(재동의 대기 없음) 건강정보 동의가 유효할 때만 싣는다.
     *
     * <p>#59 에서 조건이 하나 더 붙는다 — "미완료 보고 파기 요청({@code report_purge_request.completed_at is null})이 없을 것" (entity-design
     * §1-5: 철회 직후 재동의한 회원의 새 보고를 2차 파기가 지우지 않게). 파기 요청 테이블과 철회 API 가 #59 라 지금은 파기 요청이 생길 수 없다.
     */
    public Set<String> scopesFor(MemberConsentStatusInfo consentStatus) {
        if (consentStatus.pendingRequiredConsents().isEmpty() && consentStatus.healthInfoAgreed()) {
            return Set.of(SecurityScope.REPORT_WRITE);
        }
        return Set.of();
    }
}
