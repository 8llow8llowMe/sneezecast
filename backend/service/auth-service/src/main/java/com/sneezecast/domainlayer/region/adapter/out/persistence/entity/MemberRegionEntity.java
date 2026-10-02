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
public class MemberRegionEntity extends BaseEntity implements Persistable<Long> {

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

    /**
     * 새 행인지 판정한다 — 감사 컬럼 {@code createdAt} 은 persist 때 Auditing 이 채우므로, 비어 있으면 아직 저장되지 않은 엔티티다.
     *
     * <p>Snowflake 로 ID 를 미리 정해 두기 때문에 기본 판정(ID null 여부)은 항상 "기존 행" 이 되어 {@code save} 가 merge 로 간다. 그러면
     * INSERT 전에 SELECT 가 한 번 더 나가고, ID 가 겹치면 기존 행을 조용히 덮어쓴다. 이 판정으로 persist 를 타게 한다.
     *
     * <p><b>주의</b>: 도메인 모델에서 새로 매핑한 엔티티는 항상 새 행으로 본다. 기존 행을 고칠 때는 {@link #changeDistrictCode} 를 쓴다.
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
