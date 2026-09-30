package com.sneezecast.security.common.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.security.common.exception.SecurityErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 기본 writer 가 공통 응답 봉투를 쓰는지 고정한다.
 *
 * <p><b>이 테스트가 있어야 하는 이유</b>: 서비스가 writer 를 {@code @Primary} 로 덮으면 기본값이 틀려도
 * 아무 테스트도 깨지지 않는다. 기본값이 계약이므로 여기서 직접 잡는다.
 */
class DefaultSecurityErrorResponseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultSecurityErrorResponseWriter writer =
        new DefaultSecurityErrorResponseWriter(objectMapper);

    @Test
    @DisplayName("401 을 공통 봉투로 쓴다 — dataHeader 밖으로 나가지 않는다")
    void writesEnvelopeForUnauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, SecurityErrorCode.UNAUTHORIZED, "내부 사정");

        assertThat(response.getStatus()).isEqualTo(401);
        // MockHttpServletResponse 는 setCharacterEncoding 을 합쳐 돌려준다 — 접두만 본다
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.has("dataHeader")).isTrue();
        assertThat(body.path("dataHeader").path("success").asBoolean()).isFalse();
        assertThat(body.path("dataHeader").path("resultCode").asText()).isEqualTo("SECURITY_001");
        assertThat(body.path("dataHeader").path("resultMessage").asText()).isEqualTo("인증이 필요합니다.");
        // 실패 응답이므로 본문은 비어 있다
        assertThat(body.path("dataBody").isNull()).isTrue();
    }

    @Test
    @DisplayName("detail 은 응답에 싣지 않는다 — 인증되지 않은 호출자에게 줄 정보가 아니다")
    void neverLeaksDetail() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, SecurityErrorCode.TOKEN_SIGNATURE_INVALID, "HS512 서명 불일치 kid=abc");

        assertThat(response.getContentAsString()).doesNotContain("HS512", "kid=abc");
    }

    @ParameterizedTest
    @EnumSource(SecurityErrorCode.class)
    @DisplayName("어느 시큐리티 오류 코드든 봉투와 상태를 그대로 옮긴다")
    void writesEveryCodeInsideEnvelope(SecurityErrorCode errorCode) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, errorCode, null);

        assertThat(response.getStatus()).isEqualTo(errorCode.getHttpStatus().value());
        JsonNode header = objectMapper.readTree(response.getContentAsString()).path("dataHeader");
        assertThat(header.path("resultCode").asText()).isEqualTo(errorCode.getCode());
        assertThat(header.path("resultMessage").asText()).isEqualTo(errorCode.getMessage());
    }
}
