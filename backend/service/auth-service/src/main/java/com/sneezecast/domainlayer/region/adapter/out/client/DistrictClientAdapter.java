package com.sneezecast.domainlayer.region.adapter.out.client;

import com.sneezecast.domainlayer.region.adapter.out.client.feign.DistrictClient;
import com.sneezecast.domainlayer.region.adapter.out.client.feign.dto.DistrictClientResponse;
import com.sneezecast.domainlayer.region.application.exception.RegionErrorCode;
import com.sneezecast.domainlayer.region.application.exception.RegionException;
import com.sneezecast.domainlayer.region.application.port.out.DistrictQueryPort;
import com.sneezecast.domainlayer.region.application.port.out.query.DistrictQueryResult;
import com.sneezecast.global.client.InternalClientRejectedException;
import com.sneezecast.global.client.InternalClientSupport;
import com.sneezecast.global.client.InternalServiceUnavailableException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * surveillance 행정동 내부 API 호출. 응답의 뜻을 여기서 정한다.
 *
 * <ul>
 *   <li>200 → 행정동 (폐지 코드도 {@code active=false} 로 온다)</li>
 *   <li>404 + {@code DISTRICT_001} → 없는 코드 → empty</li>
 *   <li>그 밖의 4xx(경로가 없는 기본 404 = 상대가 옛 버전, 400 {@code DISTRICT_103} = 형식 규칙이 서로 어긋남)와 5xx · timeout · 서킷 오픈 →
 *       {@code REGION_004}(503). 계약 밖 응답을 "없는 코드" 로 읽으면 멀쩡한 동네를 사용자에게 다시 고르게 만든다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistrictClientAdapter implements DistrictQueryPort {

    /** surveillance {@code DistrictErrorCode.DISTRICT_NOT_FOUND}. 서비스 간 계약이라 문자열로 고정한다. */
    static final String DISTRICT_NOT_FOUND_CODE = "DISTRICT_001";

    private final DistrictClient districtClient;
    private final InternalClientSupport internalClientSupport;

    @Override
    public Optional<DistrictQueryResult> findByCode(String code) {
        DistrictClientResponse body;
        try {
            body = internalClientSupport.requestAndUnwrap(InternalClientSupport.SURVEILLANCE_SERVICE, () -> districtClient.getDistrict(code));
        } catch (InternalClientRejectedException exception) {
            if (exception.isStatus(HttpStatus.NOT_FOUND.value()) && DISTRICT_NOT_FOUND_CODE.equals(exception.getResultCode())) {
                return Optional.empty();
            }
            // 행정동 코드는 회원 식별정보가 아니다. 계약이 어긋난 것이라 원인을 남긴다.
            log.error("District lookup rejected outside contract target={} status={} resultCode={} districtCode={}",
                exception.getTargetService(), exception.getStatus(), exception.getResultCode(), code, exception);
            throw new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE, exception);
        } catch (InternalServiceUnavailableException exception) {
            // 원인(서킷 오픈 · timeout · 5xx)은 cause 에 있다. 스택까지 남겨 어느 쪽인지 로그로 가른다.
            log.warn("District lookup unavailable target={} districtCode={}", exception.getTargetService(), code, exception);
            throw new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE, exception);
        }
        if (body == null || !code.equals(body.code())) {
            // 200 인데 본문이 없거나 물은 코드와 다른 행정동이 왔다 — 계약 밖이다. 저장 판단을 할 수 없으니 장애로 본다.
            // (다른 코드를 그대로 저장하면 사용자가 고르지 않은 동네가 남는다.)
            log.error("District lookup returned unexpected body districtCode={} returnedCode={}", code, body == null ? null : body.code());
            throw new RegionException(RegionErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
        }
        return Optional.of(DistrictQueryResult.builder()
            .code(body.code())
            .name(body.name())
            .sigungu(body.sigungu())
            .active(body.active())
            .build());
    }
}
