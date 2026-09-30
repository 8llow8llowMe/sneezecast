package com.sneezecast.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sneezecast.common.dto.DataHeader;
import com.sneezecast.common.dto.Response;
import com.sneezecast.common.dto.ValidationErrorItem;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ValidationErrorSupportTest {

    private static final String DEFAULT_CODE = "MEMBER_100";
    private static final String OBJECT_NAME = "signupRequest";

    private record SignupRequest(String email, String password, String nickname) {
    }

    private enum GradeType { SMALL, MEDIUM, LARGE }

    private record ProfileRequest(String name, GradeType sizeType, List<ProfileRequest> friends) {
    }

    // ── 요청 본문을 읽지 못한 경우 (HttpMessageNotReadableException) ─────────────

    @Test
    @DisplayName("enum 에 없는 값은 필드명과 허용 값을 함께 알려 준다 — 어디를 고칠지 알 수 있어야 한다")
    void unreadableBodyWithUnknownEnumValue() throws Exception {
        HttpMessageNotReadableException exception = unreadable("{\"name\":\"몽실이\",\"sizeType\":\"HUGE\"}");

        DataHeader header = headerOf(exception);

        assertThat(header.fieldErrors()).hasSize(1);
        assertThat(header.fieldErrors().getFirst().code()).isEqualTo(DEFAULT_CODE);
        assertThat(header.fieldErrors().getFirst().field()).isEqualTo("sizeType");
        assertThat(header.resultMessage()).contains("sizeType").contains("SMALL, MEDIUM, LARGE");
    }

    @Test
    @DisplayName("중첩 배열 안의 필드는 items[0].field 꼴 경로로 가리킨다")
    void unreadableBodyPointsNestedPath() throws Exception {
        HttpMessageNotReadableException exception =
            unreadable("{\"name\":\"몽실이\",\"friends\":[{\"name\":\"보리\",\"sizeType\":\"HUGE\"}]}");

        assertThat(headerOf(exception).fieldErrors().getFirst().field()).isEqualTo("friends[0].sizeType");
    }

    @Test
    @DisplayName("깨진 JSON 은 필드를 특정할 수 없어 request 로 두고 형식을 확인하라고 안내한다")
    void unreadableBodyWithBrokenJson() throws Exception {
        HttpMessageNotReadableException exception = unreadable("{\"name\": ");

        DataHeader header = headerOf(exception);

        assertThat(header.fieldErrors().getFirst().field()).isEqualTo("request");
        assertThat(header.resultMessage()).contains("JSON");
    }

    /** 실제 Jackson 이 던지는 원인 예외를 그대로 감싼다 — Spring 의 메시지 컨버터가 하는 일과 같다. */
    private static HttpMessageNotReadableException unreadable(String json) {
        try {
            new ObjectMapper().readValue(json, ProfileRequest.class);
            throw new IllegalStateException("역직렬화가 실패해야 하는 입력이다: " + json);
        } catch (JsonParseException | com.fasterxml.jackson.databind.JsonMappingException cause) {
            return new HttpMessageNotReadableException("JSON parse error", cause, new MockHttpInputMessage(json.getBytes()));
        } catch (java.io.IOException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private static DataHeader headerOf(HttpMessageNotReadableException exception) {
        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(exception, DEFAULT_CODE);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        Response<Void> payload = response.getBody();
        assertThat(payload).isNotNull();
        return payload.dataHeader();
    }

    /** 직접 호출하지 않는다 — {@code MethodArgumentNotValidException} 이 요구하는 {@code MethodParameter} 를 리플렉션으로 얻는 자리다. */
    private void handler(SignupRequest request) {
    }

    @Test
    @DisplayName("한 필드에 제약이 여러 개면 필수 → 길이 → 형식 순으로 정렬하고, 필드는 DTO 선언 순서를 따른다")
    void sortsByDeclarationOrderThenConstraintPriority() throws Exception {
        BindingResult bindingResult = bindingResult();
        // 일부러 뒤섞어서 넣는다. Bean Validation은 제약 평가 순서를 보장하지 않는다.
        bindingResult.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));
        bindingResult.addError(fieldError("nickname", "NotBlank", "MEMBER_108:닉네임은 필수입니다."));
        bindingResult.addError(fieldError("password", "Size", "MEMBER_104:8자 이상 20자 이하여야 합니다."));
        bindingResult.addError(fieldError("email", "Email", "MEMBER_102:이메일 형식이 올바르지 않습니다."));

        List<ValidationErrorItem> errors = errorsOf(bindingResult);

        assertThat(errors).extracting(ValidationErrorItem::code)
            .containsExactly("MEMBER_102", "MEMBER_104", "MEMBER_105", "MEMBER_108");
        assertThat(errors).extracting(ValidationErrorItem::field)
            .containsExactly("email", "password", "password", "nickname");
    }

    @Test
    @DisplayName("추가 순서가 달라도 같은 결과를 내려 resultCode가 흔들리지 않는다")
    void producesSameResultRegardlessOfInsertionOrder() throws Exception {
        BindingResult first = bindingResult();
        first.addError(fieldError("password", "Size", "MEMBER_104:8자 이상 20자 이하여야 합니다."));
        first.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));

        BindingResult second = bindingResult();
        second.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));
        second.addError(fieldError("password", "Size", "MEMBER_104:8자 이상 20자 이하여야 합니다."));

        assertThat(resultCodeOf(first)).isEqualTo("MEMBER_104");
        assertThat(resultCodeOf(second)).isEqualTo("MEMBER_104");
        assertThat(errorsOf(first)).isEqualTo(errorsOf(second));
    }

    @Test
    @DisplayName("대표 메시지는 정렬된 첫 오류의 메시지이고 코드 접두어는 제거된다")
    void representativeMessageComesFromFirstSortedError() throws Exception {
        BindingResult bindingResult = bindingResult();
        bindingResult.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));
        bindingResult.addError(fieldError("password", "NotBlank", "MEMBER_103:비밀번호는 필수입니다."));

        DataHeader header = headerOf(bindingResult);

        assertThat(header.resultMessage()).isEqualTo("비밀번호는 필수입니다.");
        assertThat(header.fieldErrors().getFirst().code()).isEqualTo("MEMBER_103");
    }

    @Test
    @DisplayName("코드 접두어가 없는 메시지는 호출부가 넘긴 기본 코드를 사용한다")
    void fallsBackToDefaultCodeWhenPrefixMissing() throws Exception {
        BindingResult bindingResult = bindingResult();
        bindingResult.addError(fieldError("email", "Email", "이메일 형식이 올바르지 않습니다."));

        List<ValidationErrorItem> errors = errorsOf(bindingResult);

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().code()).isEqualTo(DEFAULT_CODE);
        assertThat(errors.getFirst().message()).isEqualTo("이메일 형식이 올바르지 않습니다.");
    }

    // ── 응답 봉투의 타입 계약 ─────────────────────────────────────

    /**
     * 이 이슈의 본체 — <b>직렬화된 JSON 에서</b> {@code resultMessage} 가 문자열인지 고정한다.
     * 자바 타입이 이미 String 이라 컴파일러가 막아 주지만, 계약을 읽는 쪽이 보는 것은 JSON 이다.
     * 예전에는 이 자리가 {@code {"message":..., "errors":[...]}} 객체여서, 문자열을 기대하던
     * 프론트가 서버 문구를 버리고 "API 오류 (status 400)" 을 대신 보여줬다.
     */
    @Test
    @DisplayName("검증 오류도 resultMessage 는 JSON 문자열이고 필드 목록은 fieldErrors 로 나간다")
    void validationFailureKeepsResultMessageAsJsonString() throws Exception {
        BindingResult bindingResult = bindingResult();
        bindingResult.addError(fieldError("email", "Email", "MEMBER_102:이메일 형식이 올바르지 않습니다."));

        JsonNode header = serialize(bindingResult).get("dataHeader");

        assertThat(header.get("resultMessage").isTextual()).isTrue();
        assertThat(header.get("resultMessage").asText()).isEqualTo("이메일 형식이 올바르지 않습니다.");
        assertThat(header.get("resultCode").asText()).isEqualTo("MEMBER_102");
        assertThat(header.get("fieldErrors").isArray()).isTrue();
        assertThat(header.get("fieldErrors").get(0).get("field").asText()).isEqualTo("email");
    }

    /** 검증이 아닌 오류와 <b>같은 타입</b>이어야 통일이다. 한쪽만 문자열이면 고친 것이 아니다. */
    @Test
    @DisplayName("검증이 아닌 오류는 resultMessage 타입이 같고 fieldErrors 만 null 이다")
    void nonValidationFailureHasNullFieldErrors() {
        JsonNode header = new ObjectMapper()
            .valueToTree(Response.fail("PLAN_001", "존재하지 않는 여행 일정입니다."))
            .get("dataHeader");

        assertThat(header.get("resultMessage").isTextual()).isTrue();
        assertThat(header.get("fieldErrors").isNull()).isTrue();
    }

    private JsonNode serialize(BindingResult bindingResult) throws Exception {
        Method method = getClass().getDeclaredMethod("handler", SignupRequest.class);
        MethodArgumentNotValidException exception =
            new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);
        return new ObjectMapper().valueToTree(ValidationErrorSupport.toResponse(exception, DEFAULT_CODE).getBody());
    }

    private BindingResult bindingResult() {
        return new BeanPropertyBindingResult(new SignupRequest("bad", "abc", ""), OBJECT_NAME);
    }

    /**
     * Spring이 Bean Validation 실패로 만드는 FieldError 모양을 흉내낸다.
     * codes 의 첫 항목이 {@code "제약명.객체명.필드명"} 이라서 여기서 제약 애노테이션 이름을 뽑는다.
     */
    private FieldError fieldError(String field, String constraint, String message) {
        String[] codes = {constraint + "." + OBJECT_NAME + "." + field, constraint + "." + field, constraint};
        return new FieldError(OBJECT_NAME, field, null, false, codes, null, message);
    }

    private DataHeader headerOf(BindingResult bindingResult) throws Exception {
        Method method = getClass().getDeclaredMethod("handler", SignupRequest.class);
        MethodArgumentNotValidException exception =
            new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);

        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(exception, DEFAULT_CODE);
        Response<Void> payload = response.getBody();
        assertThat(payload).isNotNull();
        return payload.dataHeader();
    }

    private List<ValidationErrorItem> errorsOf(BindingResult bindingResult) throws Exception {
        return headerOf(bindingResult).fieldErrors();
    }

    private String resultCodeOf(BindingResult bindingResult) throws Exception {
        Method method = getClass().getDeclaredMethod("handler", SignupRequest.class);
        MethodArgumentNotValidException exception =
            new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);

        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(exception, DEFAULT_CODE);
        Response<Void> payload = response.getBody();
        assertThat(payload).isNotNull();
        return payload.dataHeader().resultCode();
    }
}
