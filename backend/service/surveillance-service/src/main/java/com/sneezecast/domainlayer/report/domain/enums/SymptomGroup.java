package com.sneezecast.domainlayer.report.domain.enums;

import com.sneezecast.common.dto.metadata.CodeNameDescribable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 증상군 (entity-design §7). {@code weekly_report.symptom_mask} 에 비트로 저장한다 — 개별 증상(발열 · 기침 …)은 화면 안내 문구일 뿐 저장하지 않는다.
 *
 * <p><b>비트 값은 고정 계약이다</b> — RESPIRATORY = 1, ENTERIC = 2. 바꾸면 저장된 보고의 증상군이 뒤바뀐다. 증상군을 늘릴 때는 다음 비트(4)를
 * 쓰고, 기존 값은 바꾸지도 다시 쓰지도 않는다. 컬럼이 TINYINT(부호 있는 1바이트)라 쓸 수 있는 비트는 64 까지(7개)다.
 *
 * <p>마스크 0 은 "증상 없음" 이다. 건강한 주간 보고가 집계의 분모라서 빈 집합도 정상 보고다.
 */
@Getter
@RequiredArgsConstructor
public enum SymptomGroup implements CodeNameDescribable {
    RESPIRATORY(1, "호흡기", "발열 · 기침 · 인후통"),
    ENTERIC(2, "장관", "구토 · 설사");

    /** 증상 없음. */
    public static final int NO_SYMPTOM_MASK = 0;

    private static final int KNOWN_BITS = Arrays.stream(values()).mapToInt(SymptomGroup::getBit).reduce(NO_SYMPTOM_MASK, (left, right) -> left | right);

    private final int bit;
    private final String displayName;
    private final String description;

    /**
     * 증상군 집합을 마스크로 바꾼다. 빈 집합은 {@value #NO_SYMPTOM_MASK}(증상 없음)이다.
     *
     * @throws NullPointerException 집합이 null 이거나 null 원소가 있는 경우 — 조용히 접으면 증상 보고가 증상 없음으로 바뀐다
     */
    public static int toMask(Collection<SymptomGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        int mask = NO_SYMPTOM_MASK;
        for (SymptomGroup group : groups) {
            mask |= Objects.requireNonNull(group, "group").bit;
        }
        return mask;
    }

    /**
     * 마스크를 증상군 집합으로 되돌린다. 결과는 선언 순서(RESPIRATORY → ENTERIC)로 도는 읽기 전용 집합이다.
     *
     * @throws IllegalArgumentException 음수이거나 정의되지 않은 비트가 있는 경우 — 모르는 비트를 버리면 저장된 증상이 조용히 사라진다
     */
    public static Set<SymptomGroup> fromMask(int mask) {
        if (mask < 0 || (mask & ~KNOWN_BITS) != 0) {
            throw new IllegalArgumentException("알 수 없는 증상군 비트가 있습니다. mask=" + mask);
        }
        EnumSet<SymptomGroup> groups = EnumSet.noneOf(SymptomGroup.class);
        for (SymptomGroup group : values()) {
            if ((mask & group.bit) != 0) {
                groups.add(group);
            }
        }
        return Collections.unmodifiableSet(groups);
    }
}
