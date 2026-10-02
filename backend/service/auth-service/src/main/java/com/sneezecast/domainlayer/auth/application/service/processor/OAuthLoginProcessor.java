package com.sneezecast.domainlayer.auth.application.service.processor;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.OAuthAuthorizationInfo;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginDecision;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthAuthorizationUrlPort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthLoginStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthMemberQueryPort;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.domainlayer.auth.application.service.support.EmailMasker;
import com.sneezecast.domainlayer.auth.application.service.support.OAuthNicknameNormalizer;
import com.sneezecast.domainlayer.auth.application.service.support.OAuthOneTimeValueGenerator;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.support.EmailNormalizer;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.OAuthLoginProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 소셜(카카오) 로그인의 트랜잭션 밖 구간 — state 발급 · 검증, 제공자 사용자 정보, 회원 판정, 가입표 · 연결 확인표 발급 · 소비. DB 쓰기는
 * {@link OAuthMemberProcessor} 가 맡는다.
 *
 * <p><b>회원 식별은 이메일이다.</b> 제공자 회원 ID 는 저장하지 않으므로, 제공자가 인증 · 유효하다고 알린 이메일만 믿는다. 이메일이 없거나 믿을 수 없으면
 * 가입도 연결도 하지 않는다.
 *
 * <p>state · 표 · 인가 코드 · 이메일 원문은 로그 · 예외 메시지에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginProcessor {

    private final OAuthAuthorizationUrlPort oAuthAuthorizationUrlPort;
    private final OAuthMemberQueryPort oAuthMemberQueryPort;
    private final OAuthLoginStorePort oAuthLoginStorePort;
    private final MemberRepositoryPort memberRepositoryPort;
    private final MailSendPort mailSendPort;
    private final OAuthOneTimeValueGenerator oneTimeValueGenerator;
    private final OAuthLoginProperties properties;

    /**
     * 일회용 state 를 만들어 저장하고 그 state 를 실은 인가 주소를 돌려준다. state 는 web 어댑터가 쿠키로도 심는다(double-submit).
     *
     * <p>인증 없이 부르는 API 라 호출마다 state 키가 수명(기본 10분)만큼 남는다. IP 시도 횟수를 state 저장 <b>전에</b> 올리고 상한을 넘으면
     * {@code OAUTH_AUTHORIZE_IP_LIMITED}(429)로 막는다 — Redis(refresh 세션 · 블랙리스트와 공유)를 state 로 채우지 못하게 한다. 카운터 장애는 fail-open.
     *
     * @param clientIp IP 상한의 키로만 쓴다. 저장 · 로그하지 않는다
     */
    public OAuthAuthorizationInfo authorize(OAuthProvider provider, boolean switchAccount, String clientIp) {
        if (oAuthLoginStorePort.increaseAuthorizeIpCount(clientIp, properties.authorizeIpWindow()) > properties.authorizeIpMaxCount()) {
            throw new AuthException(AuthErrorCode.OAUTH_AUTHORIZE_IP_LIMITED);
        }
        String state = oneTimeValueGenerator.generate();
        oAuthLoginStorePort.saveState(state, provider, properties.stateTtl());
        return new OAuthAuthorizationInfo(oAuthAuthorizationUrlPort.authorizationUrl(state, switchAccount), state);
    }

    /**
     * 콜백의 state 가 <b>이 브라우저</b>가 받은 것인지 확인하고 소비한다.
     *
     * <ul>
     *   <li>Redis 만 보면 인가 주소를 발급받은 브라우저와 콜백을 보낸 브라우저가 다를 수 있다 — 공격자가 자기 인가 주소를 피해자에게 열게 하면 피해자
     *       브라우저가 공격자 계정으로 로그인된다(login CSRF). 인가 응답에 심은 쿠키와 대조하면 공격자의 state 는 피해자 브라우저에 없어 거부된다.</li>
     *   <li><b>쿠키 대조를 Redis 소비보다 먼저 한다.</b> 순서가 뒤집히면 어차피 거부될 요청이 멀쩡한 state 를 태워 정상 사용자의 콜백까지 막는다.</li>
     *   <li>비교는 {@link MessageDigest#isEqual} 로 한다 — 응답 시간 차이로 state 를 한 바이트씩 맞춰 볼 표면을 만들지 않는다.</li>
     *   <li>쿠키 없음 · 불일치 · 저장소에 없음 · 다른 제공자의 state 는 모두 같은 {@code OAUTH_STATE_INVALID} 다.</li>
     * </ul>
     */
    public void consumeState(OAuthProvider provider, String state, String cookieState) {
        if (isBlank(state) || isBlank(cookieState)) {
            throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID);
        }
        if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8))) {
            log.warn("oauth state cookie mismatch provider={}", provider);
            throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID);
        }
        OAuthProvider issuedFor = oAuthLoginStorePort.consumeState(state).orElseThrow(() -> new AuthException(AuthErrorCode.OAUTH_STATE_INVALID));
        if (issuedFor != provider) {
            throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID);
        }
    }

    /**
     * 인가 코드로 제공자의 사용자 정보를 받고, 회원으로 받아들일 수 있는지 본다. 원격 호출이라 트랜잭션 밖에서 부른다.
     *
     * @throws AuthException 이메일이 없으면 {@code OAUTH_EMAIL_REQUIRED}, 인증되지 않았거나 유효하지 않으면 {@code OAUTH_EMAIL_UNVERIFIED}. 미인증
     *                       이메일을 믿으면 남의 이메일로 가입하거나 남의 이메일 계정에 연결하는 길이 생긴다.
     */
    public OAuthMemberQueryResult fetchMember(String authorizationCode) {
        OAuthMemberQueryResult member = oAuthMemberQueryPort.fetchMember(authorizationCode);
        if (isBlank(member.email())) {
            throw new AuthException(AuthErrorCode.OAUTH_EMAIL_REQUIRED);
        }
        if (!member.emailVerified() || !member.emailValid()) {
            throw new AuthException(AuthErrorCode.OAUTH_EMAIL_UNVERIFIED);
        }
        return member;
    }

    /**
     * 이메일로 회원을 찾아 다음 단계를 정한다.
     *
     * <ul>
     *   <li>없음 → <b>가입표</b>. 가입 동의 전에는 회원 행을 만들지 않는다(개인정보 수집 동의가 수집보다 먼저). 카카오 값은 표에만 둔다.</li>
     *   <li>탈퇴 · 정지 → {@code MEMBER_002} · {@code MEMBER_003}. 카카오가 이메일 소유를 확인했으므로 상태를 알려도 된다(이메일 로그인의 "비밀번호가
     *       맞을 때만" 과 같은 조건).</li>
     *   <li>같은 제공자가 연결된 회원 → 로그인.</li>
     *   <li>이메일 계정(제공자 없음) → <b>연결 확인표</b>. 자동으로 연결하지 않고 사용자 확인을 받는다.</li>
     *   <li>다른 제공자가 연결된 회원 → {@code OAUTH_LINK_NOT_ALLOWED}. 지금은 제공자가 카카오 하나라 생기지 않는다.</li>
     * </ul>
     */
    public OAuthLoginDecision resolve(OAuthProvider provider, OAuthMemberQueryResult oAuthMember) {
        String email = EmailNormalizer.normalize(oAuthMember.email());
        Optional<Member> found = memberRepositoryPort.findByEmail(email);
        if (found.isEmpty()) {
            String nickname = OAuthNicknameNormalizer.normalize(oAuthMember.nickname());
            String ticket = oneTimeValueGenerator.generate();
            oAuthLoginStorePort.saveSignupTicket(OAuthOneTimeValueGenerator.hash(ticket), new OAuthSignupTicket(provider, email, nickname),
                properties.signupTicketTtl());
            return OAuthLoginDecision.signupRequired(ticket, nickname);
        }

        Member member = found.get();
        GeneralLoginProcessor.requireActive(member);
        if (member.provider() == provider) {
            return OAuthLoginDecision.loggedIn(member);
        }
        if (member.provider() != null) {
            throw new AuthException(AuthErrorCode.OAUTH_LINK_NOT_ALLOWED);
        }
        String ticket = oneTimeValueGenerator.generate();
        oAuthLoginStorePort.saveLinkTicket(OAuthOneTimeValueGenerator.hash(ticket), new OAuthLinkTicket(member.id(), provider), properties.linkTicketTtl());
        return OAuthLoginDecision.linkRequired(ticket, EmailMasker.mask(email));
    }

    /** 가입표를 원자적으로 꺼내고 지운다(1회성). 없음 · 만료 · 이미 씀이면 {@code OAUTH_SIGNUP_TICKET_EXPIRED} — 화면은 카카오 로그인부터 다시 한다. */
    public OAuthSignupTicket consumeSignupTicket(String ticket) {
        if (isBlank(ticket)) {
            throw new AuthException(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED);
        }
        return oAuthLoginStorePort.consumeSignupTicket(OAuthOneTimeValueGenerator.hash(ticket))
            .orElseThrow(() -> new AuthException(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED));
    }

    /** 연결 확인표를 원자적으로 꺼내고 지운다(1회성). 없음 · 만료 · 이미 씀이면 {@code OAUTH_LINK_TICKET_EXPIRED}. */
    public OAuthLinkTicket consumeLinkTicket(String ticket) {
        if (isBlank(ticket)) {
            throw new AuthException(AuthErrorCode.OAUTH_LINK_TICKET_EXPIRED);
        }
        return oAuthLoginStorePort.consumeLinkTicket(OAuthOneTimeValueGenerator.hash(ticket))
            .orElseThrow(() -> new AuthException(AuthErrorCode.OAUTH_LINK_TICKET_EXPIRED));
    }

    /** 연결 사실을 메일로 알린다 — 본인이 하지 않았으면 바로 알아챌 수 있게. 비동기라 실패해도 연결 · 로그인은 성공이다. */
    public void notifyLinked(Member member) {
        mailSendPort.sendOAuthLinkedNotice(member.email());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
