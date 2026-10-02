package com.sneezecast.domainlayer.districtimport.domain.model;

import java.util.regex.Pattern;

/**
 * SGIS 스냅샷에서 읽은 읍면동 한 건. 테이블 구조의 정본은 surveillance-service 의 {@code DistrictEntity} 다.
 *
 * <p>생성 시 코드 형식과 시도 · 시군구 코드의 접두 관계를 검사한다. 형식이 어긋난 값이 들어오면 {@link IllegalArgumentException} 이고,
 * 원천 어댑터가 응답 해석 실패로 바꾼다.
 *
 * @param code        SGIS 읍면동 코드 8자리
 * @param name        읍면동 이름 (adm_nm 마지막 토큰)
 * @param sidoCode    코드 앞 2자리
 * @param sidoName    시도 이름
 * @param sigunguCode 코드 앞 5자리
 * @param sigunguName 시군구 이름
 */
public record ImportedDistrict(String code, String name, String sidoCode, String sidoName, String sigunguCode, String sigunguName) {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\d{8}");

    public ImportedDistrict {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("district code must be 8 digits. code=" + code);
        }
        if (!code.substring(0, 2).equals(sidoCode) || !code.substring(0, 5).equals(sigunguCode)) {
            throw new IllegalArgumentException("sido/sigungu code must be prefix of district code. code=%s sidoCode=%s sigunguCode=%s"
                .formatted(code, sidoCode, sigunguCode));
        }
        requireText(name, "name", code);
        requireText(sidoName, "sidoName", code);
        requireText(sigunguName, "sigunguName", code);
    }

    /**
     * 행 PK. SGIS 코드를 숫자로 바꾼 값이다 — district 는 Snowflake 를 쓰지 않고, 같은 코드는 언제 적재해도 같은 id 가 되어야
     * {@code ON DUPLICATE KEY UPDATE} 가 PK · {@code uk_district_code} 어느 쪽으로 걸려도 같은 행을 가리킨다.
     */
    public long id() {
        return Long.parseLong(code);
    }

    private static void requireText(String value, String field, String code) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s must not be blank. code=%s".formatted(field, code));
        }
    }
}
