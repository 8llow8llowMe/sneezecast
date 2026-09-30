package com.sneezecast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
    "com.sneezecast.domainlayer",
    "com.sneezecast.global"
})
public class SurveillanceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SurveillanceServiceApplication.class, args);
    }

}
