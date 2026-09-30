package com.sneezecast;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 기본 프로파일(dev)로 컨텍스트가 뜨는지 본다.
 *
 * <p>local 프로파일이 없으므로 dev 설정을 그대로 쓰고, 배포 때 env 로 들어오는 값만 여기서 채운다.
 * dev yml 의 자리표시자가 늘면 이 테스트가 먼저 알려 준다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "SERVICE_DISCOVERY_PORT=0"
})
class ServiceDiscoveryApplicationTests {

    @Test
    void contextLoads() {
    }

}
