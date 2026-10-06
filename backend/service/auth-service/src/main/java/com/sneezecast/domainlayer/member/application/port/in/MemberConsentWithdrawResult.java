package com.sneezecast.domainlayer.member.application.port.in;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;

/**
 * 동의 철회 결과. 응답 본문과 함께, 이번 요청으로 철회가 일어나 모든 기기를 로그아웃시켰는지(컨트롤러가 refresh 쿠키를 지울지)를 싣는다.
 *
 * <p>응답 DTO 를 실어 나르므로 {@code application/info} 가 아니라 port/in 반환 DTO 로 둔다 (architecture-guide 의 허용 예외).
 *
 * @param loggedOut 이번에 철회해 모든 기기 로그아웃을 시도했는지. 이미 철회했거나 동의한 적이 없으면(멱등) false — 세션 · 쿠키를 건드리지 않는다
 */
public record MemberConsentWithdrawResult(
    MemberConsentStatusResponse response,
    boolean loggedOut
) {

}
