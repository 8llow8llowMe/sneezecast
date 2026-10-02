package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공공데이터포털 질병관리청 전수신고 감염병 발생현황 API 설정 (data-api-analysis §2).
 *
 * <p>인증키가 비어 있어도 기동은 성공한다 — 주 1회 잡 하나 때문에 상주 프로세스 전체가 뜨지 못하면 안 된다. 키 검사는 잡을 실행할 때 원천
 * 어댑터가 하고, 없으면 {@code KDCA_CREDENTIALS_MISSING} 으로 실패한다.
 *
 * <p><b>인증키는 Decoding 값을 넣는다.</b> 어댑터가 요청할 때 URI 변수로 한 번 인코딩한다. Encoding 값(이미 {@code %2B} 처럼 인코딩된 값)을
 * 넣으면 {@code %} 가 다시 {@code %25} 로 인코딩되어 인증이 깨지므로, 어댑터가 호출 전에 {@link #serviceKeyLooksEncoded()} 로 막는다.
 *
 * @param baseUrl            API 기본 주소 (오퍼레이션 앞까지)
 * @param serviceKey         공공데이터포털 인증키 Decoding 값 ({@code KDCA_API_SERVICE_KEY})
 * @param connectTimeout     연결 timeout
 * @param readTimeout        응답 timeout. 주별 한 해(약 200KB)가 4 ~ 5초에 왔다
 * @param pageSize           {@code numOfRows}. 5000 은 받는 것을 확인했고 그 이상은 시험하지 않았다
 * @param maxPagesPerRequest 조회 조건 하나에 넘길 페이지 상한. 연간 주별이 약 3,600행이라 보통 1페이지로 끝난다
 */
@ConfigurationProperties(prefix = "kdca")
public record KdcaProperties(
    @DefaultValue("https://apis.data.go.kr/1790387/EIDAPIService") String baseUrl,
    String serviceKey,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("5000") int pageSize,
    @DefaultValue("5") int maxPagesPerRequest
) {

    private static final String MASK = "****";

    public KdcaProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("kdca.base-url must not be blank");
        }
        requirePositive(connectTimeout, "kdca.connect-timeout");
        requirePositive(readTimeout, "kdca.read-timeout");
        if (pageSize < 1) {
            throw new IllegalArgumentException("kdca.page-size must be positive. value=" + pageSize);
        }
        if (maxPagesPerRequest < 1) {
            throw new IllegalArgumentException("kdca.max-pages-per-request must be positive. value=" + maxPagesPerRequest);
        }
    }

    public boolean hasServiceKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** {@code %} 가 있으면 Encoding 값으로 본다. Decoding 값은 영숫자와 {@code + / =} 뿐이다. */
    public boolean serviceKeyLooksEncoded() {
        return serviceKey != null && serviceKey.contains("%");
    }

    /** 인증키는 값도 길이도 드러내지 않는다 (coding-conventions §10). */
    @Override
    public String toString() {
        return "KdcaProperties[baseUrl=%s, serviceKey=%s, connectTimeout=%s, readTimeout=%s, pageSize=%s, maxPagesPerRequest=%s]"
            .formatted(baseUrl, MASK, connectTimeout, readTimeout, pageSize, maxPagesPerRequest);
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " must be positive. value=" + value);
        }
    }
}
