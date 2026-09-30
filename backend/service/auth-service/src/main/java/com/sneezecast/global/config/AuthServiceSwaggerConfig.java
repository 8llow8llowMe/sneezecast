package com.sneezecast.global.config;

import com.sneezecast.common.config.SwaggerSecurityConfigurer;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * OpenAPI 문서. 문서 엔드포인트는 prod 프로파일에서 끈다 (application-prod.yml 의 springdoc 설정).
 */
@Configuration
@Import(SwaggerSecurityConfigurer.class)
public class AuthServiceSwaggerConfig {

    @Bean
    public OpenAPI authServiceOpenApi(Components swaggerComponents) {
        return new OpenAPI()
            .components(swaggerComponents)
            .info(new Info()
                .title("우리동네체온계 회원 · 인증 서비스 API")
                .description("auth-service — 회원, 인증, 동의, 행정동 설정, 알림")
                .version("v1"))
            // 상대 경로로 두어 문서를 불러온 origin 기준으로 호출하게 한다. 내부 컨테이너 주소가 잡혀 CORS 로 막히는 일을 피한다.
            .servers(List.of(new Server().url("/").description("auth-service")));
    }
}
