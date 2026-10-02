package com.sneezecast.apigateway.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.error.ErrorWebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.WebExceptionHandler;

/**
 * 두 게이트웨이 핸들러를 함께 올려도 기본 오류 핸들러가 살아 있고, 둘 다 기본 핸들러보다 앞에 서는지 고정한다.
 * 띄운 게이트웨이에서 실제 응답이 봉투로 나가는지는 {@code ApiGatewayApplicationTests} 가 본다.
 */
class GatewayExceptionWebHandlerWiringTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class, ErrorWebFluxAutoConfiguration.class))
        .withBean(ObjectMapper.class)
        .withBean(JwtAuthExceptionWebHandler.class)
        .withBean(GatewayExceptionWebHandler.class);

    @Test
    @DisplayName("게이트웨이 핸들러를 올려도 부트 기본 오류 핸들러가 사라지지 않는다 — 매핑 밖 오류는 여전히 기본이 맡는다")
    void defaultErrorHandlerSurvives() {
        contextRunner.run(context -> assertThat(context)
            .hasSingleBean(ErrorWebExceptionHandler.class)
            .hasSingleBean(JwtAuthExceptionWebHandler.class)
            .hasSingleBean(GatewayExceptionWebHandler.class));
    }

    @Test
    @DisplayName("두 게이트웨이 핸들러가 모두 기본 오류 핸들러보다 먼저 선다 — 뒤에 서면 404 · 503 · 504 가 기본 형식으로 나간다")
    void gatewayHandlersAreOrderedBeforeTheDefault() {
        contextRunner.run(context -> {
            List<WebExceptionHandler> handlers =
                new ArrayList<>(context.getBeansOfType(WebExceptionHandler.class).values());
            AnnotationAwareOrderComparator.sort(handlers);

            int defaultIndex = indexOf(handlers, ErrorWebExceptionHandler.class);
            assertThat(defaultIndex).as("기본 오류 핸들러가 후보여야 한다").isNotNegative();
            assertThat(indexOf(handlers, GatewayExceptionWebHandler.class)).isBetween(0, defaultIndex - 1);
            assertThat(indexOf(handlers, JwtAuthExceptionWebHandler.class)).isBetween(0, defaultIndex - 1);
        });
    }

    private static int indexOf(List<WebExceptionHandler> handlers, Class<?> type) {
        for (int i = 0; i < handlers.size(); i++) {
            if (type.isInstance(handlers.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
