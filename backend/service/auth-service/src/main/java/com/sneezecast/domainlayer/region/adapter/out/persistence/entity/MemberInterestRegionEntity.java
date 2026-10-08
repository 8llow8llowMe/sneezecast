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
import org.springframework.data.domain.Persistable;

/**
 * 회원이 고른 관심 동네 (entity-design §1-6). 회원당 여러 행이고 회원은 raw FK 로만 참조한다(DB FK 제약 없음). 행정동은 다른 서비스(surveillance
 * {@code district})의 코드라 값만 들고 있다. 고치는 경로가 없다 — 넣고 지우기만 한다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "member_interest_region",
    indexes = {
        // 같은 동네를 두 번 고르지 못하게 DB 가 지킨다. 회원 기준 조회(목록) 인덱스도 겸한다 — member_id 가 앞 컬럼이다.
        @Index(name = MemberInterestRegionEntity.MEMBER_DISTRICT_UNIQUE_INDEX, columnList = "memberId, districtCode", unique = true),
        // 회원당 상한을 DB 가 지킨다 — 칸(slot)이 1..max-count 뿐이라, 동시에 들어온 추가 요청이 같은 빈 칸을 노리면 하나는 여기서 막힌다.
        @Index(name = MemberInterestRegionEntity.MEMBER_SLOT_UNIQUE_INDEX, columnList = "memberId, slot", unique = true)
    })
public class MemberInterestRegionEntity extends BaseEntity implements Persistable<Long> {

    public static final String MEMBER_DISTRICT_UNIQUE_INDEX = "uk_member_interest_region_member_id_district_code";
    public static final String MEMBER_SLOT_UNIQUE_INDEX = "uk_member_interest_region_member_id_slot";

    @Id
    @Comment("회원 관심 동네 아이디 (Snowflake). 오름차순이 고른 순서다")
    private Long id;

    @Column(nullable = false)
    @Comment("회원 아이디 (FK: member.id)")
    private Long memberId;

    @Column(length = 8, nullable = false)
    @Comment("행정동 코드 (SGIS 8자리, surveillance district.code). 저장 전 내부 API 로 현행 코드인지 검증한다")
    private String districtCode;

    @Column(nullable = false)
    @Comment("관심 동네 칸 번호 (1..region.interest.max-count). 회원당 상한을 unique 로 지키는 데만 쓰고 순서가 아니다 — 지운 칸을 다시 쓴다")
    private int slot;

    /**
     * 새 행인지 판정한다 — {@code MemberRegionEntity#isNew} 와 같은 이유(Snowflake ID 를 미리 정해 기본 판정이 merge 로 가면 겹치는 ID 를 조용히
     * 덮어쓴다)로 감사 컬럼 {@code createdAt} 이 비어 있으면 새 행이다 (coding-conventions §8-1).
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
