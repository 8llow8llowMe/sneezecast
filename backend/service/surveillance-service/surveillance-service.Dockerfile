# ARM64 · x86_64 공용 JRE 21 (멀티 아키텍처 이미지)
# (dev 배포 대상 backend-1 은 aarch64 다. prod 미니PC 아키텍처는 미확인. 빌더는 x86_64 이지만 JAR 은 아키텍처 무관이다)
FROM ibm-semeru-runtimes:open-21-jre-jammy

# Jenkins 에서 사전 빌드된 JAR 복사
# (`./gradlew :service:surveillance-service:bootJar` 산출물을 배포 에이전트가 app.jar 로 배치한 것)
ARG JAR_FILE=./app.jar
COPY ${JAR_FILE} /app/surveillance-service.jar

# 컨테이너 메모리 인식 + heap 70% 상한, 시간대 / 프로파일 환경변수 주입
# TIME_ZONE 은 Vault 키 표에 없어 비면 Asia/Seoul 로 둔다 (주차 경계 · 스케줄 시각이 KST 기준이다).
ENTRYPOINT ["sh", "-c", "java \
  -Duser.timezone=${TIME_ZONE:-Asia/Seoul} \
  -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=70.0 \
  -XX:InitialRAMPercentage=30.0 \
  -jar /app/surveillance-service.jar"]
