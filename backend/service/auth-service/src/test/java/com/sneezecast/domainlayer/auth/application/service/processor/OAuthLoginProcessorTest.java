package com.sneezecast.domainlayer.auth.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.auth.application.exception.AuthErrorCode;
import com.sneezecast.domainlayer.auth.application.exception.AuthException;
import com.sneezecast.domainlayer.auth.application.info.OAuthAuthorizationInfo;
import com.sneezecast.domainlayer.auth.application.model.OAuthLinkTicket;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginDecision;
import com.sneezecast.domainlayer.auth.application.model.OAuthLoginOutcome;
import com.sneezecast.domainlayer.auth.application.model.OAuthSignupTicket;
import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthAuthorizationUrlPort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthLoginStorePort;
import com.sneezecast.domainlayer.auth.application.port.out.OAuthMemberQueryPort;
import com.sneezecast.domainlayer.auth.application.port.out.query.OAuthMemberQueryResult;
import com.sneezecast.domainlayer.auth.application.service.support.OAuthOneTimeValueGenerator;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.sneezecast.domainlayer.member.domain.enums.MemberStatus;
import com.sneezecast.domainlayer.member.domain.enums.OAuthProvider;
import com.sneezecast.domainlayer.member.domain.model.Member;
import com.sneezecast.global.properties.OAuthLoginProperties;
import com.sneezecast.security.common.enums.SecurityRole;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthLoginProcessorTest {

    private static final OAuthProvider KAKAO = OAuthProvider.KAKAO;
    private static final OAuthLoginProperties PROPERTIES = new OAuthLoginProperties(Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(10), 3,
        Duration.ofMinutes(10));
    private static final String EMAIL = "user@example.com";
    private static final String IP = "203.0.113.10";

    private FakeOAuthLoginStore store;
    private OAuthMemberQueryPort memberQueryPort;
    private MemberRepositoryPort memberRepositoryPort;
    private MailSendPort mailSendPort;
    private OAuthLoginProcessor processor;

    @BeforeEach
    void setUp() {
        store = new FakeOAuthLoginStore();
        memberQueryPort = mock(OAuthMemberQueryPort.class);
        memberRepositoryPort = mock(MemberRepositoryPort.class);
        mailSendPort = mock(MailSendPort.class);
        OAuthAuthorizationUrlPort urlPort = (state, switchAccount) -> "https://kauth.test/oauth/authorize?state=" + state + "&switch=" + switchAccount;
        processor = new OAuthLoginProcessor(urlPort, memberQueryPort, store, memberRepositoryPort, mailSendPort, new OAuthOneTimeValueGenerator(), PROPERTIES);
    }

    @Test
    @DisplayName("인가 — 43자 무작위 state 를 state 수명으로 저장하고 그 state 를 실은 주소를 돌려준다")
    void authorizeStoresStateAndBuildsUrl() {
        OAuthAuthorizationInfo info = processor.authorize(KAKAO, true, IP);

        assertThat(info.state()).matches("[A-Za-z0-9_-]{43}");
        assertThat(store.states).containsEntry(info.state(), KAKAO);
        assertThat(store.ttls).containsEntry("state:" + info.state(), PROPERTIES.stateTtl());
        assertThat(info.authorizeUrl()).contains("state=" + info.state()).contains("switch=true");
        assertThat(processor.authorize(KAKAO, false, IP).state()).isNotEqualTo(info.state());
        assertThat(store.ipWindows).containsEntry(IP, PROPERTIES.authorizeIpWindow());
    }

    @Test
    @DisplayName("인가 IP 상한 — 상한까지는 통과하고, 넘는 요청은 state 를 저장하기 전에 OAUTH_AUTHORIZE_IP_LIMITED(429) 로 막는다. 다른 IP 는 영향 없다")
    void authorizeIpLimitStopsBeforeSavingState() {
        for (int i = 0; i < PROPERTIES.authorizeIpMaxCount(); i++) {
            processor.authorize(KAKAO, false, IP);
        }
        int saved = store.states.size();

        assertThat(authFailure(() -> processor.authorize(KAKAO, false, IP))).isEqualTo(AuthErrorCode.OAUTH_AUTHORIZE_IP_LIMITED);
        assertThat(AuthErrorCode.OAUTH_AUTHORIZE_IP_LIMITED.getHttpStatus().value()).isEqualTo(429);
        assertThat(store.states).hasSize(saved);
        processor.authorize(KAKAO, false, "198.51.100.7");
        assertThat(store.states).hasSize(saved + 1);
    }

    @Test
    @DisplayName("인가 IP 카운터가 장애로 0 이면(fail-open) 막지 않는다")
    void authorizeIpCounterFailOpen() {
        store.failOpen = true;
        for (int i = 0; i <= PROPERTIES.authorizeIpMaxCount(); i++) {
            processor.authorize(KAKAO, false, IP);
        }
        assertThat(store.states).hasSize(PROPERTIES.authorizeIpMaxCount() + 1);
    }

    @Test
    @DisplayName("state — 쿠키 · 콜백 값이 같으면 한 번 소비되고, 같은 state 를 다시 쓰면 OAUTH_STATE_INVALID 다")
    void stateIsConsumedOnce() {
        String state = processor.authorize(KAKAO, false, IP).state();

        processor.consumeState(KAKAO, state, state);

        assertThat(store.states).doesNotContainKey(state);
        assertThat(stateFailure(state, state)).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);
    }

    @Test
    @DisplayName("state — 쿠키가 없거나 다르면 OAUTH_STATE_INVALID 이고, 저장소를 소비하지 않는다(쿠키 대조가 먼저) — 정상 사용자의 state 가 남는다")
    void cookieMismatchDoesNotConsumeStore() {
        String state = processor.authorize(KAKAO, false, IP).state();

        assertThat(stateFailure(state, null)).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);
        assertThat(stateFailure(state, " ")).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);
        assertThat(stateFailure(state, "attacker-state")).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);
        assertThat(stateFailure(null, state)).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);

        assertThat(store.consumeStateCalls).isZero();
        assertThat(store.states).containsKey(state);
        processor.consumeState(KAKAO, state, state);
    }

    @Test
    @DisplayName("state — 쿠키와 같아도 저장소에 없으면(만료 · 발급한 적 없음) OAUTH_STATE_INVALID 다")
    void unknownStateIsInvalid() {
        assertThat(stateFailure("never-issued", "never-issued")).isEqualTo(AuthErrorCode.OAUTH_STATE_INVALID);
        assertThat(store.consumeStateCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자 정보 — 이메일이 없으면 OAUTH_EMAIL_REQUIRED, 인증되지 않았거나 유효하지 않으면 OAUTH_EMAIL_UNVERIFIED 다")
    void emailMustBePresentAndTrusted() {
        assertThat(fetchFailure(OAuthMemberQueryResult.builder().email(null).emailVerified(true).emailValid(true).build()))
            .isEqualTo(AuthErrorCode.OAUTH_EMAIL_REQUIRED);
        assertThat(fetchFailure(OAuthMemberQueryResult.builder().email(" ").emailVerified(true).emailValid(true).build()))
            .isEqualTo(AuthErrorCode.OAUTH_EMAIL_REQUIRED);
        assertThat(fetchFailure(OAuthMemberQueryResult.builder().email(EMAIL).emailVerified(false).emailValid(true).build()))
            .isEqualTo(AuthErrorCode.OAUTH_EMAIL_UNVERIFIED);
        assertThat(fetchFailure(OAuthMemberQueryResult.builder().email(EMAIL).emailVerified(true).emailValid(false).build()))
            .isEqualTo(AuthErrorCode.OAUTH_EMAIL_UNVERIFIED);

        OAuthMemberQueryResult trusted = OAuthMemberQueryResult.builder().email(EMAIL).emailVerified(true).emailValid(true).nickname("닉네임").build();
        when(memberQueryPort.fetchMember("code")).thenReturn(trusted);
        assertThat(processor.fetchMember("code")).isEqualTo(trusted);
    }

    @Test
    @DisplayName("신규 이메일 → 가입표. 회원 행은 만들지 않고, 표는 해시 키로 정규화한 이메일 · 닉네임을 가입표 수명만큼 담는다")
    void newEmailGetsSignupTicket() {
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.empty());

        OAuthLoginDecision decision = processor.resolve(KAKAO, kakaoMember(" User@Example.com ", "  재채기탐정  "));

        assertThat(decision.outcome()).isEqualTo(OAuthLoginOutcome.SIGNUP_REQUIRED);
        assertThat(decision.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(decision.nickname()).isEqualTo("재채기탐정");
        String hash = OAuthOneTimeValueGenerator.hash(decision.ticket());
        assertThat(store.signupTickets).containsOnlyKeys(hash).containsEntry(hash, new OAuthSignupTicket(KAKAO, EMAIL, "재채기탐정"));
        assertThat(store.ttls).containsEntry("signup:" + hash, PROPERTIES.signupTicketTtl());
        verify(memberRepositoryPort, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("카카오가 연결된 ACTIVE 회원 → 로그인 (표를 만들지 않는다)")
    void linkedMemberLogsIn() {
        Member member = member(MemberStatus.ACTIVE, KAKAO, null);
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member));

        OAuthLoginDecision decision = processor.resolve(KAKAO, kakaoMember(EMAIL, "닉네임"));

        assertThat(decision.outcome()).isEqualTo(OAuthLoginOutcome.LOGGED_IN);
        assertThat(decision.member()).isEqualTo(member);
        assertThat(store.signupTickets).isEmpty();
        assertThat(store.linkTickets).isEmpty();
    }

    @Test
    @DisplayName("이메일 계정(제공자 없음) → 연결 확인표. 자동 연결하지 않고, 표는 회원 ID · 제공자를 담고 응답에는 가린 이메일만 준다")
    void emailAccountGetsLinkTicket() {
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE, null, "$2a$10$hash")));

        OAuthLoginDecision decision = processor.resolve(KAKAO, kakaoMember(EMAIL, "닉네임"));

        assertThat(decision.outcome()).isEqualTo(OAuthLoginOutcome.LINK_REQUIRED);
        assertThat(decision.maskedEmail()).isEqualTo("u***@example.com");
        String hash = OAuthOneTimeValueGenerator.hash(decision.ticket());
        assertThat(store.linkTickets).containsOnlyKeys(hash).containsEntry(hash, new OAuthLinkTicket(42L, KAKAO));
        assertThat(store.ttls).containsEntry("link:" + hash, PROPERTIES.linkTicketTtl());
        verify(memberRepositoryPort, never()).updateProvider(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("탈퇴 · 정지 회원은 MEMBER_002 · MEMBER_003 이고 표를 만들지 않는다")
    void inactiveMembersAreBlocked() {
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.WITHDRAWN, KAKAO, null)));
        assertThat(((MemberException) catchThrowable(() -> processor.resolve(KAKAO, kakaoMember(EMAIL, "닉")))).getErrorCode())
            .isEqualTo(MemberErrorCode.WITHDRAWN_MEMBER);

        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.SUSPENDED, null, "$2a$10$hash")));
        assertThat(((MemberException) catchThrowable(() -> processor.resolve(KAKAO, kakaoMember(EMAIL, "닉")))).getErrorCode())
            .isEqualTo(MemberErrorCode.SUSPENDED_MEMBER);

        assertThat(store.signupTickets).isEmpty();
        assertThat(store.linkTickets).isEmpty();
    }

    @Test
    @DisplayName("가입표 · 연결 확인표는 1회용이다 — 두 번째 소비 · 없는 표 · 빈 쿠키는 각각 OAUTH_SIGNUP_TICKET_EXPIRED · OAUTH_LINK_TICKET_EXPIRED 다")
    void ticketsAreSingleUse() {
        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.empty());
        String signupTicket = processor.resolve(KAKAO, kakaoMember(EMAIL, "닉네임")).ticket();

        assertThat(processor.consumeSignupTicket(signupTicket)).isEqualTo(new OAuthSignupTicket(KAKAO, EMAIL, "닉네임"));
        assertThat(authFailure(() -> processor.consumeSignupTicket(signupTicket))).isEqualTo(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED);
        assertThat(authFailure(() -> processor.consumeSignupTicket(null))).isEqualTo(AuthErrorCode.OAUTH_SIGNUP_TICKET_EXPIRED);

        when(memberRepositoryPort.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE, null, "$2a$10$hash")));
        String linkTicket = processor.resolve(KAKAO, kakaoMember(EMAIL, "닉네임")).ticket();

        assertThat(processor.consumeLinkTicket(linkTicket)).isEqualTo(new OAuthLinkTicket(42L, KAKAO));
        assertThat(authFailure(() -> processor.consumeLinkTicket(linkTicket))).isEqualTo(AuthErrorCode.OAUTH_LINK_TICKET_EXPIRED);
        assertThat(authFailure(() -> processor.consumeLinkTicket(""))).isEqualTo(AuthErrorCode.OAUTH_LINK_TICKET_EXPIRED);
        // 가입표로 연결할 수 없다 — 키 종류가 다르다.
        assertThat(authFailure(() -> processor.consumeLinkTicket(signupTicket))).isEqualTo(AuthErrorCode.OAUTH_LINK_TICKET_EXPIRED);
    }

    @Test
    @DisplayName("연결 통보 메일은 회원 이메일로 보낸다")
    void notifyLinkedSendsMail() {
        processor.notifyLinked(member(MemberStatus.ACTIVE, KAKAO, "$2a$10$hash"));

        verify(mailSendPort).sendOAuthLinkedNotice(EMAIL);
        verify(mailSendPort, never()).sendPasswordResetCode(anyString(), anyString());
    }

    private AuthErrorCode stateFailure(String state, String cookieState) {
        return authFailure(() -> processor.consumeState(KAKAO, state, cookieState));
    }

    private AuthErrorCode fetchFailure(OAuthMemberQueryResult result) {
        when(memberQueryPort.fetchMember("code")).thenReturn(result);
        return authFailure(() -> processor.fetchMember("code"));
    }

    private static AuthErrorCode authFailure(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(AuthException.class);
        return ((AuthException) thrown).getErrorCode();
    }

    private static OAuthMemberQueryResult kakaoMember(String email, String nickname) {
        return OAuthMemberQueryResult.builder().email(email).emailVerified(true).emailValid(true).nickname(nickname).build();
    }

    private static Member member(MemberStatus status, OAuthProvider provider, String password) {
        return Member.builder().id(42L).email(EMAIL).password(password).nickname("닉네임").role(SecurityRole.USER).provider(provider).status(status).build();
    }

    /** TTL 은 흉내 내지 않는다. 소비는 꺼내고 지운다(원자성은 통합 테스트가 실제 Redis 로 본다). */
    static class FakeOAuthLoginStore implements OAuthLoginStorePort {

        final Map<String, OAuthProvider> states = new HashMap<>();
        final Map<String, OAuthSignupTicket> signupTickets = new HashMap<>();
        final Map<String, OAuthLinkTicket> linkTickets = new HashMap<>();
        final Map<String, Duration> ttls = new HashMap<>();
        final Map<String, Long> ipCounts = new HashMap<>();
        final Map<String, Duration> ipWindows = new HashMap<>();
        int consumeStateCalls;
        boolean failOpen;

        @Override
        public void saveState(String state, OAuthProvider provider, Duration ttl) {
            states.put(state, provider);
            ttls.put("state:" + state, ttl);
        }

        @Override
        public Optional<OAuthProvider> consumeState(String state) {
            consumeStateCalls++;
            return Optional.ofNullable(states.remove(state));
        }

        @Override
        public void saveSignupTicket(String ticketHash, OAuthSignupTicket ticket, Duration ttl) {
            signupTickets.put(ticketHash, ticket);
            ttls.put("signup:" + ticketHash, ttl);
        }

        @Override
        public Optional<OAuthSignupTicket> consumeSignupTicket(String ticketHash) {
            return Optional.ofNullable(signupTickets.remove(ticketHash));
        }

        @Override
        public void saveLinkTicket(String ticketHash, OAuthLinkTicket ticket, Duration ttl) {
            linkTickets.put(ticketHash, ticket);
            ttls.put("link:" + ticketHash, ttl);
        }

        @Override
        public Optional<OAuthLinkTicket> consumeLinkTicket(String ticketHash) {
            return Optional.ofNullable(linkTickets.remove(ticketHash));
        }

        @Override
        public long increaseAuthorizeIpCount(String clientIp, Duration window) {
            ipWindows.put(clientIp, window);
            return failOpen ? 0L : ipCounts.merge(clientIp, 1L, Long::sum);
        }
    }
}
