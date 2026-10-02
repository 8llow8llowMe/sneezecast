package com.sneezecast.domainlayer.member.adapter.out.auth;

import com.sneezecast.domainlayer.auth.application.service.support.ReportScopePolicy;
import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberReportScopePort;
import com.sneezecast.security.common.constant.SecurityScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * member → auth 교차 의존을 어댑터로 한정한다. 판정은 로그인 · 재발급이 쓰는 {@link ReportScopePolicy} 그대로다.
 */
@Component
@RequiredArgsConstructor
public class MemberReportScopeAdapter implements MemberReportScopePort {

    private final ReportScopePolicy reportScopePolicy;

    @Override
    public boolean isReportWritable(MemberConsentStatusInfo consentStatus) {
        return reportScopePolicy.scopesFor(consentStatus).contains(SecurityScope.REPORT_WRITE);
    }
}
