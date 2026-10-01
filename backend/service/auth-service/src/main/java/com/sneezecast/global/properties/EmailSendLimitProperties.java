package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가입용 이메일 인증의 한도 · 수명 설정 ({@code auth.email-send.*}). 값은 application.yml 의 env 자리표시자 기본값이 정본이다.
 *
 * <p>IP 발송 상한을 따로 두는 이유 — 이메일 키 쿨다운만으로는 한 IP 가 서로 다른 이메일 다수로 발송을 반복하는 남용(메일 발신 평판 훼손,
 * 타인 메일함 괴롭힘)을 막지 못한다. 검증 API 의 IP 상한은 여러 이메일에 걸친 코드 대입을 늦춘다. 두 상한 모두 보조 방어라 저장소
 * 장애에는 fail-open 이다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 — 상한 0 은 "모두 거부" 인지 "제한 없음" 인지 모호하고, 틀린 값이 조용히 기본값으로 바뀌면 운영자가
 * 설정이 먹었는지 알 수 없다.
 *
 * @param ipMaxSendCount       IP 당 발송 상한 (윈도우 안). 실제로 발송한 요청만 센다
 * @param ipWindow             IP 발송 상한의 고정 윈도우
 * @param resendCooldown       이메일당 재발송 쿨다운
 * @param codeTtl              인증코드 수명. 오입력 카운터 수명도 같다
 * @param verifiedTtl          인증 완료 표시 수명 — 이 안에 가입해야 한다
 * @param maxVerifyFailures    코드 오입력 허용 횟수. 이 횟수째 틀리면 코드를 지운다
 * @param verifyIpMaxCount     IP 당 검증 요청 상한 (윈도우 안)
 * @param verifyIpWindow       IP 검증 상한의 고정 윈도우
 */
@ConfigurationProperties(prefix = "auth.email-send")
public record EmailSendLimitProperties(
    int ipMaxSendCount,
    Duration ipWindow,
    Duration resendCooldown,
    Duration codeTtl,
    Duration verifiedTtl,
    int maxVerifyFailures,
    int verifyIpMaxCount,
    Duration verifyIpWindow
) {

    public EmailSendLimitProperties {
        requirePositive("auth.email-send.ip-max-send-count", ipMaxSendCount);
        requirePositive("auth.email-send.ip-window", ipWindow);
        requirePositive("auth.email-send.resend-cooldown", resendCooldown);
        requirePositive("auth.email-send.code-ttl", codeTtl);
        requirePositive("auth.email-send.verified-ttl", verifiedTtl);
        requirePositive("auth.email-send.max-verify-failures", maxVerifyFailures);
        requirePositive("auth.email-send.verify-ip-max-count", verifyIpMaxCount);
        requirePositive("auth.email-send.verify-ip-window", verifyIpWindow);
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalStateException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }
}
