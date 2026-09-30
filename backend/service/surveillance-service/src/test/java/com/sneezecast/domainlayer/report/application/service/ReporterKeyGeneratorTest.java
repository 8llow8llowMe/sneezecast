package com.sneezecast.domainlayer.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ReporterKeyGeneratorTest {

    /** 고정 벡터용 pepper (46자). 컨텍스트 테스트(SurveillanceServiceApplicationTests)도 같은 값을 쓴다. */
    static final String PEPPER = "sneezecast-reporter-key-test-pepper-0123456789";
    static final long MEMBER_ID = 1234567890123L;
    /**
     * HMAC-SHA256(PEPPER, "1234567890123") 소문자 hex. <b>한 번 계산해 박아 둔 리터럴이다 — 테스트 안에서 다시 계산하지 않는다.</b>
     * 이 값이 깨지면 저장된 모든 보고의 {@code reporter_key} 가 달라진다 (architecture-guide §9 고정 계약). 고치지 말고 구현을 되돌린다.
     */
    static final String EXPECTED_REPORTER_KEY = "4b42a87be0f41fcdc70da95c1bdfa39b2a9610d5da1afdba4dddeb3e055c86a9";
    /** HMAC-SHA256(PEPPER, "1") — 한 자리 회원 ID 도 앞자리 0 채움 없이 10진 문자열 그대로 쓰는지 본다. */
    private static final String EXPECTED_REPORTER_KEY_OF_ONE = "a4e7f0fab09388e0e687a5bc54bc0c868466dd019bc106832b208110637caadd";
    /** SHA-256(PEPPER) 앞 8자. */
    private static final String EXPECTED_FINGERPRINT = "623b35da";

    @Test
    @DisplayName("고정 벡터 — reporter_key 는 HMAC-SHA256(pepper, memberId 10진 문자열 UTF-8) 의 소문자 hex 다")
    void matchesFixedVector() {
        ReporterKeyGenerator generator = generator(PEPPER);

        assertThat(generator.reporterKey(MEMBER_ID)).isEqualTo(EXPECTED_REPORTER_KEY);
        assertThat(generator.reporterKey(1L)).isEqualTo(EXPECTED_REPORTER_KEY_OF_ONE);
    }

    @Test
    @DisplayName("같은 pepper · 같은 회원이면 호출과 인스턴스가 달라도 같은 키다 — 같은 주 보고를 한 번만 세는 근거")
    void isDeterministic() {
        ReporterKeyGenerator first = generator(PEPPER);
        ReporterKeyGenerator second = generator(PEPPER);

        assertThat(first.reporterKey(MEMBER_ID)).isEqualTo(first.reporterKey(MEMBER_ID)).isEqualTo(second.reporterKey(MEMBER_ID));
    }

    @Test
    @DisplayName("회원이 다르면 키가 다르고, pepper 가 다르면 같은 회원도 키가 다르다")
    void differsByMemberAndPepper() {
        ReporterKeyGenerator generator = generator(PEPPER);

        assertThat(generator.reporterKey(MEMBER_ID)).isNotEqualTo(generator.reporterKey(MEMBER_ID + 1));
        assertThat(generator(PEPPER + "-other").reporterKey(MEMBER_ID)).isNotEqualTo(EXPECTED_REPORTER_KEY);
    }

    @Test
    @DisplayName("키는 64자 소문자 hex 이고 회원 ID 를 그대로 품지 않는다")
    void isLowercaseHexWithoutMemberId() {
        assertThat(generator(PEPPER).reporterKey(MEMBER_ID))
            .hasSize(64)
            .matches("[0-9a-f]{64}")
            .doesNotContain(Long.toString(MEMBER_ID));
    }

    @ParameterizedTest(name = "memberId={0}")
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("0 이하 회원 ID 는 거부한다 — 기본값 0 이 새어 들면 서로 다른 회원이 한 보고자로 합쳐진다")
    void rejectsNonPositiveMemberId(long memberId) {
        ReporterKeyGenerator generator = generator(PEPPER);

        assertThatThrownBy(() -> generator.reporterKey(memberId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining(Long.toString(memberId));
    }

    @Test
    @DisplayName("pepper 32자는 통과한다 — 경계값")
    void acceptsMinimumLengthPepper() {
        assertThatCode(() -> generator("p".repeat(ReporterKeyGenerator.MIN_PEPPER_LENGTH))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pepper 가 없거나 비었거나 32자 미만이면 기동에서 실패하고, 메시지에 pepper 값을 싣지 않는다")
    void rejectsMissingOrShortPepperWithoutLeakingIt() {
        String shortPepper = "short-pepper-" + "x".repeat(ReporterKeyGenerator.MIN_PEPPER_LENGTH - 14);

        assertThat(shortPepper).hasSize(ReporterKeyGenerator.MIN_PEPPER_LENGTH - 1);
        assertThatThrownBy(() -> generator(shortPepper))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("surveillance.reporter-key.pepper")
            .hasMessageNotContaining(shortPepper);
        assertThatThrownBy(() -> generator(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(" ".repeat(ReporterKeyGenerator.MIN_PEPPER_LENGTH))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기동 로그에는 pepper 의 지문(SHA-256 앞 8자)만 남고 pepper 값은 남지 않는다")
    void logsFingerprintOnly(CapturedOutput output) {
        generator(PEPPER);

        assertThat(output.getAll())
            .contains("fingerprint=" + EXPECTED_FINGERPRINT)
            .doesNotContain(PEPPER);
    }

    @Test
    @DisplayName("설정 record 의 toString 에 pepper 가 나오지 않는다 — 설정 덤프·디버그 로그로 새지 않게")
    void propertiesToStringMasksPepper() {
        assertThat(new ReporterKeyProperties(PEPPER).toString()).doesNotContain(PEPPER);
    }

    private static ReporterKeyGenerator generator(String pepper) {
        return new ReporterKeyGenerator(new ReporterKeyProperties(pepper));
    }
}
