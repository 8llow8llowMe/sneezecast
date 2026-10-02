package com.sneezecast.domainlayer.notifiableimport.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KdcaCallBudgetTest {

    @Test
    @DisplayName("상한까지는 쓰고, 넘는 호출은 쓰지 않고 REQUEST_BUDGET_EXCEEDED 다")
    void consumesUntilLimitThenFails() {
        KdcaCallBudget budget = new KdcaCallBudget(2);

        budget.consume();
        budget.consume();

        assertThat(budget.used()).isEqualTo(2);
        assertThat(budget.remaining()).isZero();
        assertThatThrownBy(budget::consume)
            .isInstanceOfSatisfying(NotifiableImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(NotifiableImportErrorCode.REQUEST_BUDGET_EXCEEDED);
                assertThat(exception.getMessage()).startsWith("[NOTIFIABLE_IMPORT_010]").contains("used=2", "maxCallsPerRun=2");
            });
        // 실패한 호출은 쓰지 않는다.
        assertThat(budget.used()).isEqualTo(2);
        assertThat(budget.maxCalls()).isEqualTo(2);
    }

    @ParameterizedTest(name = "maxCalls={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("상한은 양수여야 한다")
    void rejectsNonPositiveLimit(int maxCalls) {
        assertThatThrownBy(() -> new KdcaCallBudget(maxCalls)).isInstanceOf(IllegalArgumentException.class);
    }
}
