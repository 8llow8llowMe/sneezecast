package com.sneezecast.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 원본 JSON 값이 true / false 리터럴일 때만 통과하는지 본다. 항목이 없음(Java null)은 필수 검사의 몫이라 통과다. */
class JsonBooleanValidatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JsonBooleanValidator validator = new JsonBooleanValidator();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"true", "false"})
    @DisplayName("JSON true / false 는 통과한다")
    void booleanLiteralsAreValid(String json) throws Exception {
        assertThat(validator.isValid(OBJECT_MAPPER.readTree(json), null)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"null", "\"true\"", "\"false\"", "1", "0", "1.0", "{}", "[]", "\"\""})
    @DisplayName("JSON null · 문자열 · 숫자 · 객체 · 배열은 실패다 — boolean 으로 바꿔 주지 않는다")
    void otherValuesAreInvalid(String json) throws Exception {
        JsonNode node = OBJECT_MAPPER.readTree(json);

        assertThat(validator.isValid(node, null)).isFalse();
    }

    @Test
    @DisplayName("항목이 없음(Java null)은 통과한다")
    void absentIsValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
