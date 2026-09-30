package com.sneezecast.common.dto.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "코드명 메타데이터 DTO")
public record CodeNameDescriptionMetadata(
    // 여러 도메인(회원 권한, 추천 프리셋, 추이 등)이 공유하는 스키마라 예시는 중립값을 유지한다.
    // 도메인별 실제 예시는 사용하는 응답 DTO의 필드 @Schema(example = "{...JSON...}")로 지정한다.

    @Schema(description = "내부 코드 값", example = "EXAMPLE_CODE")
    String code,

    @Schema(description = "화면 표시 이름", example = "예시 이름")
    String name,

    @Schema(description = "코드 의미 설명", example = "코드 의미를 설명하는 문장입니다.")
    String description
) {

    public static CodeNameDescriptionMetadata of(String code, String name, String description) {
        return CodeNameDescriptionMetadata.builder()
            .code(code)
            .name(name)
            .description(description)
            .build();
    }
}
