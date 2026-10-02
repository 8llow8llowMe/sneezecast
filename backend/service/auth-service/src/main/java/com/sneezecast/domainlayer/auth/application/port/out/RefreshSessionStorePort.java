package com.sneezecast.domainlayer.auth.application.port.out;

import com.sneezecast.domainlayer.auth.application.model.NewRefreshSession;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotation;
import com.sneezecast.domainlayer.auth.application.model.RefreshRotationResult;
import com.sneezecast.domainlayer.auth.application.model.SessionAccessToken;
import com.sneezecast.domainlayer.auth.application.port.out.query.RefreshSessionQueryResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 로그인 세션(기기)별 refresh 상태 저장소. 세션은 TTL({@code ttl} = refresh 만료)로 사라지고, 로그인 · 회전마다 TTL 이 다시 걸린다(sliding).
 *
 * <p>저장소 장애는 {@code AuthException(SESSION_STORE_UNAVAILABLE)} 로 올린다. 키에 회원 ID 가 들어가므로 다른 회원의 세션은 구조적으로 건드릴 수
 * 없다.
 */
public interface RefreshSessionStorePort {

    /**
     * 새 세션을 저장하고, 회원의 세션이 {@code maxDevices} 를 넘으면 마지막 사용이 가장 오래된 세션부터 밀어낸다. 저장과 밀어내기는 원자적이다.
     *
     * @return 밀어낸 세션들의 마지막 access token — 호출자가 블랙리스트에 올려 밀려난 기기를 바로 끊는다. 없으면 빈 목록
     */
    List<SessionAccessToken> save(NewRefreshSession session, Duration ttl, int maxDevices);

    /**
     * 제시한 refresh jti 로 세션을 원자적으로 회전한다. 판정 규칙은 {@link RefreshRotationResult.Outcome} 을 본다.
     *
     * @param rotationGrace 직전 jti 를 동시 재발급 경합으로 봐주는 시간
     */
    RefreshRotationResult rotate(RefreshRotation rotation, Duration rotationGrace, Duration ttl);

    /** 세션 하나를 지운다. 멱등이다. 지운 세션의 마지막 access token 을 돌려준다(없었으면 empty). */
    Optional<SessionAccessToken> delete(long memberId, String sessionId);

    /**
     * {@code keepSessionId} 를 뺀 회원의 모든 세션을 지우고, 지운 세션들의 마지막 access token 을 돌려준다.
     *
     * @param keepSessionId 남길 세션. null 이면 전부 지운다
     */
    List<SessionAccessToken> deleteAllExcept(long memberId, String keepSessionId);

    /** 살아 있는 세션 목록 (마지막 사용 내림차순). 만료돼 내용이 사라진 세션 인덱스 항목은 빼고 정리한다. */
    List<RefreshSessionQueryResult> findAll(long memberId);
}
