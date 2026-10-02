package com.sneezecast.domainlayer.auth.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailMaskerTest {

    @Test
    @DisplayName("로컬 부분의 첫 글자와 도메인만 남기고, 로컬 부분 길이는 드러내지 않는다")
    void masksLocalPart() {
        assertThat(EmailMasker.mask("dg@example.com")).isEqualTo("d***@example.com");
        assertThat(EmailMasker.mask("d@example.com")).isEqualTo("d***@example.com");
        assertThat(EmailMasker.mask("donggeun.shin@example.co.kr")).isEqualTo("d***@example.co.kr");
    }

    @Test
    @DisplayName("이메일 모양이 아니면 전부 가린다")
    void masksMalformed() {
        assertThat(EmailMasker.mask(null)).isEqualTo("***");
        assertThat(EmailMasker.mask("no-at-sign")).isEqualTo("***");
        assertThat(EmailMasker.mask("@example.com")).isEqualTo("***");
        assertThat(EmailMasker.mask("user@")).isEqualTo("***");
    }
}
