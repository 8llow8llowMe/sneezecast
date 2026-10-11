package com.sneezecast.domainlayer.member.application.service.processor;

import com.sneezecast.domainlayer.member.application.command.MemberNotificationSettingChangeCommand;
import com.sneezecast.domainlayer.member.application.exception.MemberErrorCode;
import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.application.info.MemberNotificationSettingInfo;
import com.sneezecast.domainlayer.member.application.port.out.MemberNotificationSettingRepositoryPort;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 설정의 규칙 — 행이 없으면 모두 꺼짐으로 보고(조회는 쓰지 않는다), 보낸 항목만 DB 에서 바꾸며, 행은 첫 변경 때 만든다(회원당 1행).
 *
 * <p>트랜잭션은 메서드마다 건다 — 동시 첫 변경이 unique 에 막히면 그 트랜잭션이 롤백된 뒤 호출자({@code MemberNotificationSettingWebFacade})가
 * 새 트랜잭션에서 {@link #change} 를 다시 불러야 해서다. 회원 상태 확인({@link MemberQueryProcessor#getActiveMember})도 같은 트랜잭션에서 한다 — 탈퇴 ·
 * 정지 <b>뒤에 들어온</b> 요청(남은 access)이 설정 행을 다시 만들지 못하게. 탈퇴와 <b>동시에</b> 들어온 요청은 확인을 통과한 뒤 탈퇴가 커밋되면 행을
 * 남길 수 있다 — 그 고아 행은 회원 파기 때 함께 지운다(entity-design §1-7, #244).
 */
@Service
@RequiredArgsConstructor
public class MemberNotificationSettingProcessor {

    private final MemberQueryProcessor memberQueryProcessor;
    private final MemberNotificationSettingRepositoryPort memberNotificationSettingRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 회원의 알림 설정. 한 번도 바꾸지 않았으면 모두 꺼짐이고 행을 만들지 않는다.
     *
     * @throws MemberException 회원 없음 {@code MEMBER_004} · 탈퇴 {@code 002} · 정지 {@code 003}
     */
    @Transactional(readOnly = true)
    public MemberNotificationSettingInfo get(long memberId) {
        memberQueryProcessor.getActiveMember(memberId);
        return memberNotificationSettingRepositoryPort.findByMemberId(memberId)
            .map(MemberNotificationSettingInfo::from)
            .orElse(MemberNotificationSettingInfo.ALL_OFF);
    }

    /**
     * 보낸 항목만 바꾸고 바뀐 뒤의 설정 전부를 돌려준다.
     *
     * <ul>
     *   <li>행이 있으면 부분 갱신 쿼리로 보낸 항목만 DB 에서 바꾸고(같은 회원의 다른 요청이 바꾼 항목을 되돌리지 않는다), 같은 트랜잭션에서 다시 읽어
     *       응답을 만든다.</li>
     *   <li>행이 없으면 모두 꺼짐을 바탕으로 보낸 항목을 반영해 새 행을 넣는다. 조회와 삽입 사이에 같은 회원의 다른 첫 변경이 먼저 넣으면 unique 위반이
     *       {@code MEMBER_013} 이 된다.</li>
     *   <li>조회에서 본 행이 갱신 전에 사라졌으면(탈퇴 정리 등 — 갱신 0건) 이 트랜잭션에서 판단하지 않고 {@code MEMBER_013} 으로 끝낸다. 새 스냅숏에서
     *       회원 상태부터 다시 봐야 하기 때문이다.</li>
     * </ul>
     *
     * <p>{@code MEMBER_013} 이면 이 트랜잭션은 롤백되고, 호출자가 새 트랜잭션에서 한 번 다시 부른다 — 그때 행이 있으면 갱신, 없으면 삽입, 회원이
     * 탈퇴했으면 회원 상태 오류다. 처음에 조회로 갈라 행이 없을 때만 넣는 이유: 없는 행을 먼저 UPDATE 하면 MySQL(REPEATABLE READ)이 갭 잠금을 잡아,
     * 동시 첫 변경 둘이 서로의 INSERT 를 막는 교착(unique 위반이 아닌 실패)이 될 수 있다.
     *
     * @throws MemberException 회원 상태 {@code MEMBER_004} · {@code 002} · {@code 003}, 동시 첫 변경 · 행 사라짐 {@code MEMBER_013}(409)
     */
    @Transactional
    public MemberNotificationSettingInfo change(long memberId, MemberNotificationSettingChangeCommand command) {
        memberQueryProcessor.getActiveMember(memberId);
        if (memberNotificationSettingRepositoryPort.findByMemberId(memberId).isEmpty()) {
            MemberNotificationSetting created = MemberNotificationSetting.initial(snowflakeIdGenerator.generateId(), memberId)
                .changedBy(command.weeklyReport(), command.regionNotice());
            return MemberNotificationSettingInfo.from(memberNotificationSettingRepositoryPort.insert(created));
        }
        int updated = memberNotificationSettingRepositoryPort.changeByMemberId(memberId, command.weeklyReport(), command.regionNotice(), LocalDateTime.now());
        if (updated == 0) {
            throw new MemberException(MemberErrorCode.NOTIFICATION_SETTING_SAVE_CONFLICT);
        }
        return memberNotificationSettingRepositoryPort.findByMemberId(memberId)
            .map(MemberNotificationSettingInfo::from)
            .orElseThrow(() -> new IllegalStateException("member_notification_setting row disappeared after update in the same transaction"));
    }
}
