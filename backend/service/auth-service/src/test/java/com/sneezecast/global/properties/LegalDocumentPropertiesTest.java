package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegalDocumentPropertiesTest {

    @Test
    @DisplayName("문서 버전이 비면 기동에서 실패한다 — 메시지에는 키와 환경변수 이름이 들어간다")
    void blankVersionFailsStartup() {
        assertThatThrownBy(() -> new LegalDocumentProperties("2026-10-01", " ", "2026-10-01"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("legal.privacy-version")
            .hasMessageContaining("LEGAL_PRIVACY_VERSION");
        assertThatThrownBy(() -> new LegalDocumentProperties("2026-10-01", "2026-10-01", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LEGAL_SENSITIVE_HEALTH_INFO_VERSION");
    }

    @Test
    @DisplayName("document_version 컬럼(20자)을 넘는 버전은 기동에서 실패한다 — 가입 요청마다 DB 오류가 나는 것보다 낫다")
    void tooLongVersionFailsStartup() {
        assertThatThrownBy(() -> new LegalDocumentProperties("x".repeat(21), "2026-10-01", "2026-10-01"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LEGAL_TERMS_VERSION");
        assertThatCode(() -> new LegalDocumentProperties("x".repeat(20), "2026-10-01", "2026-10-01")).doesNotThrowAnyException();
    }
}
