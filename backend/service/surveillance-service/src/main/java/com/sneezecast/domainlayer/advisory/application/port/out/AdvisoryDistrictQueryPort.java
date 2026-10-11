package com.sneezecast.domainlayer.advisory.application.port.out;

/**
 * 공개 동네 안내가 행정동 코드를 확인하는 포트. 행정동은 같은 서비스의 {@code district} 컨텍스트가 소유하므로 advisory 는 그 영속 계층을 직접 부르지 않는다.
 */
public interface AdvisoryDistrictQueryPort {

    /** 폐지 여부와 무관하게 코드가 있는지. 폐지된 코드도 true 다 — 행정동 조회(#111) · 공개 집계(#212)와 같은 관례. */
    boolean existsByCode(String code);
}
