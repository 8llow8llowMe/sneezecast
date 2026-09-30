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
 * 핸들러가 <b>실제 컨텍스트에서 제자리에 서는지</b> 고정한다.
 *
 * <p>단위 테스트는 "핸들러를 부르면 봉투가 나온다" 까지만 말한다. 이 핸들러가 없던 시절의 결함은 그 앞이었다 —
 * <b>부르는 쪽이 없어</b> 토큰 거부가 전부 500 으로 나갔다. 그래서 배선 자체를 두 가지로 못박는다.
 *
 * <ul>
 *   <li><b>부트의 기본 오류 핸들러가 살아 있다.</b> {@code ErrorWebFluxAutoConfiguration} 은 기본
 *       핸들러를 {@code @ConditionalOnMissingBean(ErrorWebExceptionHandler.class)} 로 거므로,
 *       그 타입으로 빈을 올렸다면 기본 핸들러가 통째로 사라진다. 우리는 상위 타입인
 *       {@link WebExceptionHandler} 로 등록해 그 조건을 건드리지 않는다</li>
 *   <li><b>우리 핸들러가 기본 핸들러보다 앞이다.</b> {@code ExceptionHandlingWebHandler} 는 정렬
 *       순서대로 {@code onErrorResume} 을 걸기 때문에, 뒤에 서면 JwtException 을 기본 핸들러가
 *       먼저 500 으로 만들어 버린다</li>
 * </ul>
 */
class JwtAuthExceptionWebHandlerWiringTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class, ErrorWebFluxAutoConfiguration.class))
        .withBean(ObjectMapper.class)
        .withBean(JwtAuthExceptionWebHandler.class);

    @Test
    @DisplayName("우리 핸들러를 올려도 부트 기본 오류 핸들러가 사라지지 않는다 — JWT 밖의 오류는 여전히 기본이 맡는다")
    void defaultErrorHandlerSurvives() {
        contextRunner.run(context -> assertThat(context)
            .hasSingleBean(ErrorWebExceptionHandler.class)
            .hasSingleBean(JwtAuthExceptionWebHandler.class));
    }

    @Test
    @DisplayName("우리 핸들러가 기본 오류 핸들러보다 먼저 선다 — 뒤에 서면 JwtException 이 먼저 500 이 된다")
    void ourHandlerIsOrderedBeforeTheDefault() {
        contextRunner.run(context -> {
            List<WebExceptionHandler> handlers =
                new ArrayList<>(context.getBeansOfType(WebExceptionHandler.class).values());
            AnnotationAwareOrderComparator.sort(handlers);

            assertThat(handlers)
                .as("두 핸들러가 모두 후보여야 한다")
                .hasAtLeastOneElementOfType(JwtAuthExceptionWebHandler.class)
                .hasAtLeastOneElementOfType(ErrorWebExceptionHandler.class);
            assertThat(handlers.get(0))
                .as("가장 먼저 보는 핸들러")
                .isInstanceOf(JwtAuthExceptionWebHandler.class);
        });
    }
}
