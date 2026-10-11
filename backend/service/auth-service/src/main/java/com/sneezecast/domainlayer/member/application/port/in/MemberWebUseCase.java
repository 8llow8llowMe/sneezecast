package com.sneezecast.domainlayer.member.application.port.in;

import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberConsentStatusResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberMyInfoResponse;
import com.sneezecast.domainlayer.member.adapter.in.web.dto.response.MemberProfileImageResponse;
import com.sneezecast.domainlayer.member.domain.enums.ConsentType;
import com.sneezecast.storage.model.FileUploadCommand;
import java.time.Instant;

public interface MemberWebUseCase {

    MemberMyInfoResponse getMyInfo(long memberId);

    /**
     * 프로필 이미지를 올리고 회원에 연결한다. 쓰던 업로드 이미지는 연결이 커밋된 뒤 지운다.
     *
     * @param image 어댑터 경계에서 {@code MultipartFile} 을 바꾼 업로드 자료 — 형식(매직 바이트) · 크기는 스토리지 클라이언트가 검증한다
     */
    MemberProfileImageResponse changeProfileImage(long memberId, FileUploadCommand image);

    /** 프로필 이미지를 지운다(업로드 key · 소셜 URL 모두). 이미지가 없어도 성공한다(멱등). */
    void removeProfileImage(long memberId);

    /** @param nickname 검증을 통과한 닉네임(앞뒤 공백은 여기서 지운다) */
    MemberMyInfoResponse updateMyInfo(long memberId, String nickname);

    /**
     * 현재 비밀번호를 확인하고 바꾼다. 성공하면 지금 기기({@code sessionId})를 뺀 모든 기기를 로그아웃시킨다.
     *
     * @param sessionId 요청 access 의 세션(sid). null 이면 모든 기기를 로그아웃시킨다
     */
    void changePassword(long memberId, String sessionId, String currentPassword, String newPassword);

    /** 약관 재동의 · 건강정보 동의. 이미 현재 버전으로 유효하면 아무것도 하지 않고 성공한다. 응답은 동의 뒤의 상태다. */
    MemberConsentStatusResponse agreeConsent(long memberId, ConsentType type, String documentVersion);

    /**
     * 건강정보 동의를 철회하고 보고 파기를 요청한 뒤(한 트랜잭션) 모든 기기를 로그아웃시킨다(요청한 access 포함). 철회할 동의가 없으면 아무것도 하지 않는다.
     *
     * @param accessTokenId   요청 access 의 jti
     * @param accessExpiresAt 요청 access 의 만료 시각
     */
    MemberConsentWithdrawResult withdrawConsent(long memberId, ConsentType type, String accessTokenId, Instant accessExpiresAt);

    /**
     * 회원 탈퇴. 회원 행 갱신 · 보고 파기 요청 · 관심 동네 · 알림 설정 삭제를 한 트랜잭션으로 커밋한 뒤 모든 기기를 로그아웃시킨다(요청한 access 포함).
     * 로그아웃이 실패해도 탈퇴는 이미 끝났으므로 성공으로 끝난다.
     *
     * @param accessTokenId   요청 access 의 jti
     * @param accessExpiresAt 요청 access 의 만료 시각
     */
    void withdraw(long memberId, String accessTokenId, Instant accessExpiresAt);
}
