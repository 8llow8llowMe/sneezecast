package com.sneezecast.storage.model;

/**
 * 오브젝트 키의 최상위 prefix. 업로드 대상은 회원 프로필 이미지뿐이다 (증상·건강 관련 파일은 올리지 않는다). 키는 항상 {@code {prefix}/{memberId}/{yyyy}/{MM}/{uuid}.{ext}} 형식이다.
 *
 * <p>소유자(memberId)를 키에 넣어 두면, 클라이언트가 보낸 키를 회원 정보에 연결할 때
 * "내가 올린 파일인가"를 서버가 문자열 비교만으로 검증할 수 있다.
 */
public enum StorageDomain {

    MEMBER_PROFILE("members/profiles");

    private final String prefix;

    StorageDomain(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
