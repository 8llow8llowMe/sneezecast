package com.sneezecast.domainlayer.region.adapter.out.persistence.entity;

import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

/**
 * 회원이 고른 행정동 (entity-design §1-4). 회원당 1행이고 회원은 raw FK 로만 참조한다(DB FK 제약 없음). 행정동은 다른 서비스(surveillance
 * {@code district})의 코드라 값만 들고 있다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "member_region",
    indexes = {
        // 회원당 1행을 DB 가 지킨다 — 동시에 들어온 첫 저장 두 건 중 하나는 여기서 막히고, MemberRegionRepositoryAdapter 가 409 도메인 예외로 바꾼다.
        // 회원 기준 조회 인덱스도 겸한다.
        @Index(name = MemberRegionEntity.MEMBER_ID_UNIQUE_INDEX, columnList = "memberId", unique = true)
    })
public class MemberRegionEntity extends BaseEntity {

    public static final String MEMBER_ID_UNIQUE_INDEX = "uk_member_region_member_id";

    @Id
    @Comment("회원 행정동 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false)
    @Comment("회원 아이디 (FK: member.id)")
    private Long memberId;

    @Column(length = 8, nullable = false)
    @Comment("행정동 코드 (SGIS 8자리, surveillance district.code). 저장 전 내부 API 로 현행 코드인지 검증한다")
    private String districtCode;

    /**
     * 동네를 바꾼다. 조회한 엔티티에서만 부른다 — 변경 감지로 UPDATE 가 나간다. 같은 코드면 바뀐 것이 없어 UPDATE 도 나가지 않는다.
     */
    public void changeDistrictCode(String districtCode) {
        this.districtCode = districtCode;
    }
}
