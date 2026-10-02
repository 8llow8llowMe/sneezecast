package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.application.info.MemberConsentStatusInfo;

/**
 * 동의 상태로 주간 보고를 쓸 수 있는지 판정하는 계약. scope 발급 정책은 auth 가 소유하므로, member 는 이 포트로만 묻고 auth 구현에 직접 의존하지 않는다 —
 * 내 정보의 {@code reportWritable} 이 access token 의 {@code report:write} 와 같은 계산이 되게 한다.
 */
public interface MemberReportScopePort {

    boolean isReportWritable(MemberConsentStatusInfo consentStatus);
}
