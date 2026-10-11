package com.sneezecast.domainlayer.member.adapter.in.web.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;
import com.sneezecast.domainlayer.member.application.exception.MemberValidationMessage;
import com.sneezecast.global.validation.JsonBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 알림 설정 변경 요청 — 보낸 항목만 바꾼다(PATCH). <b>이 DTO 만</b> 본문을 엄격하게 받는다 — 전역 Jackson 설정은 그대로 두고, 본문을 직접 읽는
 * 역직렬화기({@link Deserializer})로 원본 값과 받은 항목 이름을 그대로 담은 뒤 Bean Validation 으로 거른다.
 *
 * <ul>
 *   <li>값은 JSON {@code true} / {@code false} 만 받는다({@link JsonBoolean}). {@code Boolean} 으로 받으면 Boot 기본 ObjectMapper 가 문자열
 *       {@code "true"} · 숫자를 boolean 으로 바꿔 넣고, JSON {@code null} 이 "보내지 않음" 과 같아진다.</li>
 *   <li>모르는 항목은 거절한다 — Boot 는 모르는 항목을 조용히 버려서(FAIL_ON_UNKNOWN_PROPERTIES 꺼짐) 오타가 성공처럼 보인다. 받은 항목 이름은
 *       응답에 되돌려 싣지 않는다. {@code @JsonAnySetter} 로 모으면 그 모음의 이름과 같은 항목이 모음을 덮어 빠져나가서 쓰지 않는다.</li>
 *   <li>항목이 하나도 없는 본문({@code {}})은 거절한다({@link #isRequest()}).</li>
 * </ul>
 *
 * <p>검증 오류는 다른 회원 요청과 같은 봉투 · {@code fieldErrors} 다(MEMBER_111~114). record 라 오류 순서가 컴포넌트 순서로 고정된다. 본문이 깨졌거나
 * 객체가 아니면(배열 · 문자열 · 없음) 역직렬화 단계의 MEMBER_100 이다.
 *
 * @param weeklyReport 원본 값. 항목이 없으면 null, JSON null 이면 {@code NullNode}
 * @param regionNotice 원본 값. 항목이 없으면 null, JSON null 이면 {@code NullNode}
 * @param unknownItems 받은 모르는 항목 이름. 검증 오류 field 이름이 {@code unknownItems} 다
 */
@JsonDeserialize(using = MemberNotificationSettingUpdateRequest.Deserializer.class)
@Schema(description = "알림 설정 변경 요청 DTO — 보낸 항목만 바꾼다. 하나 이상 보내야 하고, 값은 true / false 만 받는다")
public record MemberNotificationSettingUpdateRequest(
    @Schema(description = "주간 보고 요청 알림을 받을지 (보내지 않으면 그대로)", implementation = Boolean.class, example = "true")
    @JsonBoolean(message = MemberValidationMessage.WEEKLY_REPORT_SETTING_INVALID)
    JsonNode weeklyReport,

    @Schema(description = "동네 안내 알림을 받을지 (보내지 않으면 그대로)", implementation = Boolean.class, example = "false")
    @JsonBoolean(message = MemberValidationMessage.REGION_NOTICE_SETTING_INVALID)
    JsonNode regionNotice,

    @Schema(hidden = true)
    @Size(max = 0, message = MemberValidationMessage.NOTIFICATION_SETTING_UNKNOWN)
    Set<String> unknownItems
) {

    static final String WEEKLY_REPORT = "weeklyReport";
    static final String REGION_NOTICE = "regionNotice";

    public MemberNotificationSettingUpdateRequest {
        unknownItems = unknownItems == null ? Set.of() : Set.copyOf(unknownItems);
    }

    /**
     * 본문에 항목(아는 것이든 모르는 것이든)이 하나라도 있는지. 메서드 이름이 검증 오류 field 이름이 된다 — {@code request} 는 본문 전체를 가리키는
     * 이름이다({@code ValidationErrorSupport} 와 같다). 모르는 항목만 있으면 이 오류 대신 MEMBER_114 하나만 낸다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = MemberValidationMessage.NOTIFICATION_SETTINGS_REQUIRED)
    public boolean isRequest() {
        return weeklyReport != null || regionNotice != null || !unknownItems.isEmpty();
    }

    /** 검증을 통과한 본문만 넘긴다 — 두 값은 boolean 이거나 보내지 않은 것(null)이다. */
    public MemberNotificationSettingChangeCommand toCommand() {
        return MemberNotificationSettingChangeCommand.builder()
            .weeklyReport(weeklyReport == null ? null : weeklyReport.booleanValue())
            .regionNotice(regionNotice == null ? null : regionNotice.booleanValue())
            .build();
    }

    /**
     * 본문을 트리로 읽어 두 항목의 원본 값과 나머지 항목 이름을 담는다. 값의 형식은 여기서 판단하지 않는다 — 판단은 Bean Validation 이 해서 필드별
     * 코드(MEMBER_11x)와 {@code fieldErrors} 로 나간다. 객체가 아닌 본문만 여기서 거절한다(MEMBER_100).
     */
    static final class Deserializer extends StdDeserializer<MemberNotificationSettingUpdateRequest> {

        Deserializer() {
            super(MemberNotificationSettingUpdateRequest.class);
        }

        @Override
        public MemberNotificationSettingUpdateRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode body = context.readTree(parser);
            if (body == null || !body.isObject()) {
                throw MismatchedInputException.from(parser, MemberNotificationSettingUpdateRequest.class, "notification setting body must be a JSON object");
            }
            Set<String> unknownItems = new LinkedHashSet<>();
            body.fieldNames().forEachRemaining(name -> {
                if (!WEEKLY_REPORT.equals(name) && !REGION_NOTICE.equals(name)) {
                    unknownItems.add(name);
                }
            });
            return new MemberNotificationSettingUpdateRequest(body.get(WEEKLY_REPORT), body.get(REGION_NOTICE), unknownItems);
        }
    }
}
