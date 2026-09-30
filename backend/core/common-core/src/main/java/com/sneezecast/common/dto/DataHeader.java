package com.sneezecast.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 공통 응답 봉투의 헤더. 성공·실패를 가리지 않고 모든 응답이 이 모양이다.
 *
 * <p><b>{@code resultMessage} 는 언제나 문자열이다</b>. 타입이
 * {@code Object} 이면 Bean Validation 실패만 {@code {message, errors}} 객체가 실렸고, 클라이언트가
 * {@code typeof === 'string'} 으로 분기하면 <b>검증 오류에서만 서버 문구를 통째로 잃었다.</b>
 * 필드 단위 오류는 {@link #fieldErrors} 라는 별도 키로 뺐다 — 한 키에 두 타입을 태우지 않는다.
 */
// 성공·실패가 이 스키마 하나를 공유하므로 example 을 달지 않는다. 한쪽 예시를 박으면
// springdoc 이 그것을 모든 응답에 보여줘서, 200 응답의 Example Value 까지 실패 모양이 된다.
// 도메인 코드를 예시로 쓰면 common-core 가 특정 서비스 코드를 전 서비스 Swagger 에 노출한다.
@Schema(description = "공통 응답 헤더")
public record DataHeader(

    @Schema(description = "성공 여부")
    boolean success,

    @Schema(description = "대표 오류 코드. 성공이면 null")
    String resultCode,

    @Schema(description = "사용자에게 보여줄 대표 오류 메시지. 성공이면 null. 오류 종류와 무관하게 항상 문자열이다")
    String resultMessage,

    @Schema(description = "필드 단위 검증 오류 목록. 검증 실패가 아니면 null")
    List<ValidationErrorItem> fieldErrors
) {

    public static DataHeader ok() {
        return new DataHeader(true, null, null, null);
    }

    public static DataHeader error(String resultCode, String resultMessage) {
        return error(resultCode, resultMessage, null);
    }

    public static DataHeader error(String resultCode, String resultMessage, List<ValidationErrorItem> fieldErrors) {
        return new DataHeader(false, resultCode, resultMessage, fieldErrors);
    }
}
