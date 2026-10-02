package com.sneezecast.domainlayer.auth.application.model;

/**
 * refresh 회전 결과.
 *
 * @param revokedAccessToken {@link Outcome#REUSE_DETECTED} 로 폐기한 세션의 마지막 access token. 그 밖의 결과이거나 기록이 없으면 null
 */
public record RefreshRotationResult(Outcome outcome, SessionAccessToken revokedAccessToken) {

    public static RefreshRotationResult of(Outcome outcome) {
        return new RefreshRotationResult(outcome, null);
    }

    public enum Outcome {
        /** 세션이 없다 — 만료됐거나, 기기 수 상한으로 밀려났거나, 폐기됐다. */
        SESSION_NOT_FOUND,
        /** 제시한 jti 가 현재 jti 와 같아 회전했다. */
        ROTATED,
        /** 제시한 jti 가 직전 jti 이고 회전 직후(유예 시간 안)다 — 여러 탭의 동시 재발급 경합. 세션은 그대로다. */
        CONCURRENT_ROTATION,
        /** 그 밖의 jti — 이미 회전된 토큰의 재사용(탈취 의심). 세션을 폐기했다. */
        REUSE_DETECTED
    }
}
