package com.sneezecast.domainlayer.official.domain.model;

import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import java.util.Map;
import java.util.Optional;

/**
 * 프로그램별 원문 주소 — 사람이 같은 값을 직접 볼 수 있는 질병관리청 화면이다. <b>원천에서 확인한 주소만 둔다</b> (data-api-analysis §3).
 *
 * <ul>
 *   <li>표본감시 세 프로그램: batch 가 화면 데이터를 받는 감염병포털 화면 그 자체다 ({@code /pot/is/st/{influ|ari|gstrnftn}.do}).</li>
 *   <li>전수신고: 원천이 공공데이터포털 API 라 같은 값을 보이는 화면을 확인하지 않았다 — 주소를 지어내지 않고 비워 둔다.</li>
 * </ul>
 */
public final class OfficialSourceLink {

    private static final String SENTINEL_PORTAL = "https://dportal.kdca.go.kr/pot/is/st/";

    private static final Map<OfficialProgram, String> URLS = Map.of(
        OfficialProgram.INFLUENZA_ILI, SENTINEL_PORTAL + "influ.do",
        OfficialProgram.ARI, SENTINEL_PORTAL + "ari.do",
        OfficialProgram.ENTERIC, SENTINEL_PORTAL + "gstrnftn.do"
    );

    private OfficialSourceLink() {
    }

    /** 확인된 원문 주소. 없으면 빈 값이다 (전수신고). */
    public static Optional<String> of(OfficialProgram program) {
        return Optional.ofNullable(URLS.get(program));
    }
}
