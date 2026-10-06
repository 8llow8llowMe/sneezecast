package com.sneezecast.domainlayer.auth.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.security.common.constant.SecurityScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportScopePolicyTest {

    private final ReportScopePolicy policy = new ReportScopePolicy();

    @Test
    @DisplayName("필수 동의가 모두 유효하고 건강정보 동의가 유효하고 미완료 파기가 없으면 report:write 를 싣는다")
    void grantsReportWrite() {
        assertThat(policy.scopesFor(new MemberConsentStatusInfo(List.of(), true, false))).containsExactly(SecurityScope.REPORT_WRITE);
    }

    @Test
    @DisplayName("건강정보 동의가 없거나 철회 · 옛 버전이면 싣지 않는다")
    void withoutHealthConsent() {
        assertThat(policy.scopesFor(new MemberConsentStatusInfo(List.of(), false, false))).isEmpty();
    }

    @Test
    @DisplayName("건강정보 동의가 유효해도 재동의할 필수 항목이 남아 있으면 싣지 않는다")
    void pendingRequiredConsentBlocksScope() {
        assertThat(policy.scopesFor(new MemberConsentStatusInfo(List.of(ConsentType.PRIVACY_POLICY), true, false))).isEmpty();
    }

    @Test
    @DisplayName("미완료 보고 파기 요청이 있으면 동의가 모두 유효해도 싣지 않는다 — 철회 뒤 재동의한 새 보고를 2차 파기가 지우지 않게")
    void pendingPurgeBlocksScope() {
        assertThat(policy.scopesFor(new MemberConsentStatusInfo(List.of(), true, true))).isEmpty();
    }
}
