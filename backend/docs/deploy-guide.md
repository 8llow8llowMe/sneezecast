# 백엔드 배포 가이드

개발 서버(dev) 배포 구성의 정본이다. 파이프라인 · Dockerfile · compose 는 모두 저장소에 있다.

| 파일 | 내용 |
|------|------|
| `Jenkinsfile.backend-common.groovy` (루트) | 공용 파이프라인 — 변경 감지, 라벨 게이트, Vault 두 층 병합 · 검사, 빌드, 배포 |
| `Jenkinsfile-<service>` (루트, 5개) | 잡별 config — 경로, 호스트 포트, 서비스 경로 사용 여부, 필수 키 |
| `<모듈>/<service>.Dockerfile` | builder 가 만든 `app.jar` 만 복사하는 실행 이미지 |
| `<모듈>/docker-compose-<service>.yml` | dev · prod 서비스 블록, 컨테이너에 넘기는 env, 메모리 상한 |

공유 인프라(Jenkins · Vault · MySQL · Redis Sentinel · MinIO · nginx)의 설치와 운영은 팀 Infra 레포(`8llow8llowMe/Infra`) 문서를 따른다.
Vault **경로와 키를 어느 층에 두는지**는 Infra `vault/README.md` sneezecast 절, 호스트 배치는 Infra `README.md` §4 · §7 이다.
**포트 · discovery 값 규칙(§4 · §6 "값 규칙")과 필수 키는 이 문서와 파이프라인 검사가 기준이다.** Infra `vault/README.md`
키 표도 같은 규칙으로 맞춘다 — 둘이 어긋나면 파이프라인 검사가 배포를 막으므로, 이 문서를 고치고 Infra 를 따라 고친다.
파이프라인은 혼디가개(hondigagae)에서 이식했고 게이트 규칙이 같다. 다른 점은 Vault 두 층(§6)과 포트 규칙(§4)이다.

## 1. 배포 모델

```text
PR 생성 / 갱신
  → GitHub Actions backend-ci: 전 모듈 ./gradlew check  (머지 조건)
  → labeler: 변경 경로로 backend-<service> 라벨 자동 부착
  → Jenkins 멀티브랜치 PR 빌드: 영향받는 서비스만 test + bootJar (배포하지 않음, Vault 를 읽지 않음)
develop 에 Rebase and merge
  → Jenkins 브랜치 빌드 (잡 5개 모두 트리거)
       1) 배포 문맥 확인   변경 경로로 영향 판단 → PR 라벨로 배포 대상 판단 (fail-closed)
       2) 배포 설정 검증   Vault 공통 + 자기 서비스 경로를 읽어 병합 · 검사 (gradle 전에 실패시킨다)
       3) JAR 빌드         builder-backend: ./gradlew :<group>:<svc>:test :<group>:<svc>:bootJar
       4) dev 환경 배포    deploy-backend-dev2: Vault 재조회 → .env.runtime → docker compose up -d --build
                           → 45초 뒤 running · 재시작 0회 확인
```

`develop → dev`, `main → prod` 다. 그 외 브랜치는 배포하지 않는다. 이미지 빌드는 배포 호스트에서 일어나고,
애플리케이션은 `.env.runtime` 을 직접 읽지 않는다 — compose 가 필요한 키만 골라 컨테이너 env 로 넘긴다.

## 2. 배포 스코프 — PR 라벨 (fail-closed)

| 라벨 | 배포 대상 잡 |
|------|--------------|
| `backend-service-discovery` | `sneezecast-service-discovery` |
| `backend-api-gateway` | `sneezecast-api-gateway` |
| `backend-auth-service` | `sneezecast-auth-service` |
| `backend-surveillance-service` | `sneezecast-surveillance-service` |
| `backend-batch-service` | `sneezecast-batch-service` |

- 라벨은 `.github/labeler.yml` 이 변경 경로로 붙인다. **라벨이 하나도 없으면 아무것도 배포하지 않는다.**
  "라벨 없음 = 전체 배포" 로 넓히지 않는다.
- **`backend-core` 는 배포 라벨이 아니다.** core 만 바꾼 PR 은 어떤 서비스도 배포하지 않는다. 작성자가 배포할
  서비스 라벨을 직접 더한다(`labeler.yml` 주석). core 변경은 라벨과 무관하게 전 서비스 PR CI 는 돈다.
- 라벨 조회는 GitHub App credential(`github-app-followfollowme-jenkins`)로 한다. 조회에 실패하면 배포를 막고
  빌드를 **UNSTABLE** 로 표시한다 — credential 문제로 배포가 조용히 멈추는 것을 알아채기 위해서다.
- 라벨을 빠뜨리고 머지했다면 해당 잡을 `FORCE_DEPLOY=true` 로 수동 실행한다. 우회하는 것은 변경 감지와 라벨
  게이트뿐이고, 브랜치 규칙(PR 빌드 배포 금지)과 `SKIP_DEPLOY`, Vault 검사는 그대로다.
- **배포를 원하지 않는 PR 은 머지 전에 `backend-*` 라벨을 뗀다.** labeler 는 라벨을 더하기만 하므로 사람이 뗀
  라벨은 다시 붙지 않는다(`sync-labels: false`). 서버가 준비되기 전의 배포 구성 PR 이 대표적이다(§8 주의).

변경 경로 → 영향 판단:

| 변경 경로 | 판정 |
|-----------|------|
| `backend/<group>/<svc>/**` | 그 서비스만 `own` |
| `backend/core/**`, `backend/build.gradle`, `backend/settings.gradle`, `backend/gradle*` | 전 서비스 `own` |
| `Jenkinsfile.backend-common.groovy`, `Jenkinsfile-<svc>` | `pipeline` (PR 라벨이 다른 서비스면 CI 생략) |
| 그 외 (`backend/docs/**` 등) | `none` — 건너뜀 |

판단이 불가능하면(첫 빌드 등) 전체 빌드로 진행한다(fail-open). 배포는 여전히 라벨이 정한다.

## 3. 명명 규칙

| 항목 | 규칙 | 예 |
|------|------|----|
| Jenkins 잡 | `sneezecast-<service>` (멀티브랜치, Script Path `Jenkinsfile-<service>`) | `sneezecast-auth-service` |
| 이미지 | `sneezecast-<service>:latest` | `sneezecast-auth-service:latest` |
| 컨테이너 | `sneezecast-<service>-<env>` | `sneezecast-auth-service-dev` |
| compose 프로젝트 | `sneezecast-<service>` (파이프라인이 `-p` 로 명시) | `sneezecast-auth-service` |
| 배포 경로 | `$HOME/deploy/sneezecast/backend/<group>/<service>` (agent 컨테이너 안) | `.../service/auth-service` |
| Vault | `kv/sneezecast/backend/<env>/env` + `kv/sneezecast/backend/<env>/<service>` | §6 |
| MySQL DB | `sneezecast_auth`, `sneezecast_surveillance` | §8 |
| 배포 lock | `sneezecast-backend-deploy` (Lockable Resource) | |

compose 프로젝트명을 디렉터리 이름에 맡기지 않는 이유: 같은 호스트의 다른 프로젝트가 같은 basename
(`auth-service`)을 쓰면 한 프로젝트로 묶여 `--remove-orphans` 가 상대 컨테이너를 지운다.

## 4. 포트

sneezecast 대역은 **dev `3xxx` / prod `4xxx`** 다 (Infra README §4 배치 원칙 5번).

| 서비스 | dev 호스트 포트 (`192.168.0.13`) | prod 호스트 포트 (미니PC, IP 미정) | publish 범위 | Vault 포트 키 | Vault 위치 |
|--------|------|------|------|------|------|
| api-gateway | 3000 | 4000 | 사설망 (nginx `.12` → `.13:3000`) | `API_GATEWAY_PORT` | api-gateway |
| service-discovery | 3761 | 4761 | 사설망 (클라이언트가 `.13:3761` 로 붙음) | `SERVICE_DISCOVERY_PORT` | 공통 (`env`) |
| auth-service | 3081 | 4081 | 호스트 루프백 `127.0.0.1` | `AUTH_SERVICE_PORT` | auth-service |
| surveillance-service | 3082 | 4082 | 호스트 루프백 `127.0.0.1` | `SURVEILLANCE_SERVICE_PORT` | surveillance-service |
| batch-service | 3080 | 4080 | 호스트 루프백 `127.0.0.1` | `BATCH_SERVICE_PORT` | batch-service |

**값 규칙: 컨테이너 내부 포트 = 호스트 포트다.** Vault 포트 키에는 위 호스트 포트를 그대로 넣는다(dev `API_GATEWAY_PORT=3000`,
`SERVICE_DISCOVERY_PORT=3761`). compose 는 `"${API_GATEWAY_PORT}:${API_GATEWAY_PORT}"`(루프백 대상은
`"127.0.0.1:${AUTH_SERVICE_PORT}:${AUTH_SERVICE_PORT}"`)로 매핑한다. 파이프라인이 `Jenkinsfile-<service>` 의 `hostPorts` 와
대조해 다르면 배포 전에 실패한다. 혼디가개(내부 8xxx 고정 + 호스트 포트 별도 키)와 다른 이유:

- Eureka 클라이언트는 **사설 IP + `SERVICE_DISCOVERY_PORT`** 로 discovery 에 붙는다(§5). 이 포트가 호스트에 같은
  번호로 열려 있어야 한다.
- 다른 서비스도 같은 규칙을 쓰면 Eureka 에 등록되는 포트(`server.port`)가 곧 호스트 포트라 헷갈릴 일이 없고,
  호스트 포트용 키(`*_PORT_DEV`)를 Vault 에 따로 둘 필요가 없다.

### 서비스 포트는 외부에 노출하지 않는다

인그레스(nginx, Infra `nginx/conf.d/api-dev.sneezecast.conf`)는 **게이트웨이 3000 만** 프록시한다.

- **auth · surveillance · batch 는 호스트 루프백(`127.0.0.1`)에만 publish 한다.** 게이트웨이는 이 포트가 아니라 Eureka 에
  등록된 브리지 IP 로 컨테이너에 직접 붙으므로(§5) publish 가 서비스 간 호출에 필요하지 않다. 루프백 publish 는
  `.13` 에서 하는 로컬 점검(§9)용이고, LAN 의 다른 호스트에서는 닿지 않는다.
- discovery 와 gateway 만 사설망에 publish 한다 — 각각 Eureka 클라이언트의 사설 IP 접속과 nginx(`.12`) 프록시 때문이다.
- 이렇게 막는 이유: 서비스 actuator(health, info, prometheus)가 인증 없이 열려 있고, access token 블랙리스트 확인 ·
  클라이언트가 보낸 회원 헤더 제거 · 업스트림 타임아웃은 게이트웨이가 한다. surveillance 는 Redis 를 쓰지 않아
  폐기 토큰을 스스로 막지 못한다.

그래서 혼디가개 · BossPickSeoul 의 auth 직결("단독") 라우트를 두지 않는다. nginx 에 서비스 포트로 가는
location 을 만들지 않고, 라우트가 필요하면 게이트웨이 라우트(`application-dev.yml` · `application-prod.yml`)에 추가한다.

## 5. 서비스 간 통신

| 경로 | 주소 |
|------|------|
| 서비스 → Eureka(discovery) | `SERVICE_DISCOVERY_HOSTNAME`(사설 IP, dev `192.168.0.13`) + `SERVICE_DISCOVERY_PORT`(dev 3761) |
| 게이트웨이 → 업스트림 서비스 | Eureka 등록 주소 (`prefer-ip-address` — 같은 호스트 `8llow8llowme-net` 브리지 IP + 서비스 포트) |
| 서비스 → MySQL · Redis · MinIO | 다른 호스트라 사설 IP (Infra README §4 원칙 4번) |
| nginx(`.12`) → 게이트웨이 | `192.168.0.13:3000` |

**값 규칙: `SERVICE_DISCOVERY_HOSTNAME` 은 discovery 호스트의 사설 IP 다 (dev `192.168.0.13`).** 컨테이너명이 아니다.
Infra 원칙 4번(호스트 간은 사설 IP)을 따르고, 파이프라인이 클라이언트 잡(gateway · auth · surveillance · batch)에서
기대값과 대조한다(prod 는 IP 미정이라 비어 있지 않은지만 본다). 내부 포트 = 호스트 포트(§4)라 컨테이너명으로 바꿔도
동작은 하지만, 검사를 통과하지 못한다.

업스트림 호출은 Eureka 가 돌려주는 브리지 IP 로 가므로 **5종이 같은 호스트 · 같은 `8llow8llowme-net` 에 있어야 한다.**
서비스를 다른 호스트로 나누려면 `eureka.instance.ip-address` 를 사설 IP 로 등록하도록 yml 을 바꾸고, 루프백 publish(§4)도 다시 정해야 한다.

## 6. Vault

### 경로 두 층

```text
kv/sneezecast/backend/{env}/env                    # 공통 — 둘 이상의 서비스가 같은 값을 써야 하는 키
kv/sneezecast/backend/{env}/api-gateway
kv/sneezecast/backend/{env}/auth-service
kv/sneezecast/backend/{env}/surveillance-service   # REPORTER_KEY_PEPPER 는 여기에만
kv/sneezecast/backend/{env}/batch-service
```

service-discovery 는 공통만 읽고 서비스 경로가 없다. 키를 어느 층에 둘지 · 무작위 값 생성 방법은 Infra
`vault/README.md` sneezecast 절을 따른다. 값은 키별로 넣는다(Web UI JSON 토글 또는 `kv put` / `kv patch`).

### 파이프라인 규칙

| 규칙 | 동작 | 위치 (`Jenkinsfile.backend-common.groovy`) |
|------|------|------|
| 자기 경로만 읽는다 | Vault 루트는 상수(`kv/sneezecast/backend`)이고 서비스 경로는 `config.serviceName` 으로만 조립한다. 경로를 바꾸는 잡 파라미터가 없다(혼디가개의 `PROJECT_SLUG` · `VAULT_SECRET_ROOT` · `VAULT_SECRET_PATH` 제거) | `resolveVaultSecretRoot`, `resolveVaultSpecs` |
| 서비스 경로 사용은 명시 | `Jenkinsfile-<service>` 의 `readServiceSecret: true/false`. 키가 없으면 실패. discovery 만 `false` | `readsServiceSecret` |
| 경로가 없거나 비면 실패 | `readServiceSecret: true` 인데 서비스 경로가 없으면(404) "공통만 쓴다" 로 넘어가지 않고 실패 | `readVaultSecretValues` |
| `.env` 로 안전하게 쓸 수 없는 값은 실패 | 줄바꿈, `$`, 따옴표로 시작, 공백 뒤 `#`. compose `--env-file` 이 치환 · 따옴표 제거 · 주석 처리로 값을 바꾸기 때문이다 | `toEnvFileValues` |
| 같은 키가 두 경로에 있으면 실패 | 덮어쓰지 않는다. 키 이름과 두 경로만 메시지에 남긴다 | `mergeVaultSecretValues` |
| 서비스 전용 키는 주인 경로에만 | `REPORTER_KEY_PEPPER` → surveillance, `AUTH_DB_*` → auth, `SURVEILLANCE_DB_*` → surveillance, `BATCH_DB_*` → batch. 공통이나 다른 서비스 경로에 있으면 실패 | `validateServiceOnlyKeys` |
| 필수 키 · 프로필 · 포트 | 아래 표의 키가 비면 실패. `SPRING_PROFILES_ACTIVE` 는 배포 환경(dev/prod)과 같아야 하고, 포트 키는 `hostPorts` 와 같아야 한다 | `validateDeployEnvValues` |
| discovery 주소 | 모든 잡의 `SERVICE_DISCOVERY_PORT` 가 discovery 포트(dev 3761 / prod 4761)와, 클라이언트 잡의 `SERVICE_DISCOVERY_HOSTNAME` 이 dev `192.168.0.13` 과 같아야 한다 | `validateDiscoveryEndpoint`, `discoveryEndpoints` |
| 값은 로그에 남기지 않는다 | 경로별 **키 이름**만 출력한다. AppRole 자격 · Vault 토큰 · GitHub 토큰은 curl 인자가 아니라 stdin 으로 넘긴다 | `readDeployEnvValues`, `loginToVault` |

검사는 gradle 전("배포 설정 검증")과 배포 직전에 두 번 한다. PR 빌드는 Vault 를 읽지 않는다.

`REPORTER_KEY_PEPPER` 격리는 두 겹이다 — 파이프라인이 auth 잡에서 surveillance 경로를 읽지 않아 auth 의
`.env.runtime` 에 pepper 가 없고, auth compose 는 그 키를 컨테이너에 넘기지 않는다. Vault 정책
(`jenkins-sneezecast`)은 `backend/*` 전체를 읽으므로 Vault 단에서는 막지 않는다(Infra vault/README 참고).

### 필수 키 (파이프라인이 검사)

yml 에 기본값이 없는 자리표시자다. 모든 잡이 `SPRING_PROFILES_ACTIVE` 와 자기 포트 키를 추가로 검사한다.
목록의 정본은 각 `Jenkinsfile-<service>` 의 `requiredEnvKeys` 다.

| 서비스 | 공통 경로에서 | 서비스 경로에서 |
|--------|---------------|-----------------|
| service-discovery | `SPRING_PROFILES_ACTIVE`, `SERVICE_DISCOVERY_PORT` | (경로 없음) |
| api-gateway | `SPRING_PROFILES_ACTIVE`, `SERVICE_DISCOVERY_HOSTNAME`, `SERVICE_DISCOVERY_PORT`, `JWT_ACCESS_KEY`, `REDIS_MASTER_NAME`, `REDIS_SENTINEL_NODES`, `REDIS_PASSWORD`, `AUTH_SERVICE_APP_NAME`, `SURVEILLANCE_SERVICE_APP_NAME` | `API_GATEWAY_PORT`, `GATEWAY_TRUSTED_PROXIES` |
| auth-service | `SPRING_PROFILES_ACTIVE`, `SERVICE_DISCOVERY_HOSTNAME`, `SERVICE_DISCOVERY_PORT`, `AUTH_SERVICE_APP_NAME`, `JWT_ACCESS_KEY`, `REDIS_MASTER_NAME`, `REDIS_SENTINEL_NODES`, `REDIS_PASSWORD` | `AUTH_SERVICE_PORT`, `AUTH_DB_URL`, `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD`, `JWT_REFRESH_KEY`, `MINIO_ENDPOINT`, `MINIO_PUBLIC_URL`, `MINIO_BUCKET`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MAIL_USERNAME`, `MAIL_PASSWORD` |
| surveillance-service | `SPRING_PROFILES_ACTIVE`, `SERVICE_DISCOVERY_HOSTNAME`, `SERVICE_DISCOVERY_PORT`, `SURVEILLANCE_SERVICE_APP_NAME`, `JWT_ACCESS_KEY` | `SURVEILLANCE_SERVICE_PORT`, `SURVEILLANCE_DB_URL`, `SURVEILLANCE_DB_USERNAME`, `SURVEILLANCE_DB_PASSWORD`, `REPORTER_KEY_PEPPER` |
| batch-service | `SPRING_PROFILES_ACTIVE`, `SERVICE_DISCOVERY_HOSTNAME`, `SERVICE_DISCOVERY_PORT` | `BATCH_SERVICE_PORT`, `BATCH_DB_URL`, `BATCH_DB_USERNAME`, `BATCH_DB_PASSWORD` |

- `JASYPT_ENCRYPTOR_KEY` 는 쓰지 않는다 (yml 에 자리표시자도 `ENC(...)` 값도 없다).
- 파이프라인은 키가 **어느 층**에 있는지까지는 보지 않는다(전용 키 제외). 위 표의 층은 Infra 키 표를 따른 것이다.
- 기본값이 있는 키(`GATEWAY_*_TIMEOUT*`, `JWT_*_EXPIRATION`, `REDIS_KEY_PREFIX`, `MINIO_MAX_FILE_BYTES`, auth 의 `MAIL_HOST` · `MAIL_PORT` ·
  `MAIL_FROM_*` · `LEGAL_*_VERSION` · `AUTH_EMAIL_SEND_*` · `AUTH_LOGIN_*` · `AUTH_SESSION_*` · `AUTH_PASSWORD_RESET_TOKEN_TTL` · `SNOWFLAKE_*` 등)는 넣지 않아도 된다. compose 가 yml 과 같은 기본값을 넘긴다 — 빈 문자열을
  넘기면 yml 기본값이 적용되지 않기 때문이다.
- `BATCH_SCHEDULE_ENABLED` 는 batch-service 경로의 Vault 값을 그대로 쓰고, 없으면 compose 가 dev `true` / prod `false` 를 넘긴다.
- auth 메일: `MAIL_USERNAME` / `MAIL_PASSWORD` 는 SMTP 계정과 앱 비밀번호다(auth 전용, 서비스 경로). `MAIL_HOST` · `MAIL_PORT` 기본값은
  Gmail(`smtp.gmail.com:587`, STARTTLS)이다. 다른 SMTP 를 쓰면 두 키를 서비스 경로에 넣는다.
- auth 동의 문서 버전 `LEGAL_TERMS_VERSION` · `LEGAL_PRIVACY_VERSION` · `LEGAL_SENSITIVE_HEALTH_INFO_VERSION` 은 20자 이하이고 비면
  기동 실패다. 정본은 프론트 legal 상수라, 문서를 개정할 때 프론트 상수와 이 값을 같은 배포에 올린다(entity-design §1-2).
- auth 이메일 인증 한도 `AUTH_EMAIL_SEND_*`(IP 발송 상한 · 윈도우, 재발송 쿨다운, 코드 수명, 인증 완료 수명, 오입력 허용 횟수, IP 검증 상한 · 윈도우)는
  기본값(application.yml)으로 충분하다. 바꿀 때만 서비스 경로에 넣는다. 0 이하면 기동 실패, 기간은 ISO-8601(`PT1H`) 형식이다.
- auth 로그인 제한 `AUTH_LOGIN_*`(이메일 실패 허용 횟수 · 잠금 시간, IP 실패 상한 · 윈도우)과 세션 `AUTH_SESSION_*`(회원당 기기 상한, 동시 재발급 허용 시간)도
  같은 규칙이다. 잠금 시간을 바꾸면 프론트 로그인 잠금 문구("10분 뒤")도 함께 바꾼다. 비밀번호 변경의 현재 비밀번호 확인 제한도 이 값을 쓴다.
- auth 비밀번호 재설정 토큰 수명 `AUTH_PASSWORD_RESET_TOKEN_TTL`(기본 `PT15M`)도 같은 규칙이다. 재설정 코드 한도는 `AUTH_EMAIL_SEND_*` 를 함께 쓴다.
- `TIME_ZONE` 은 Vault 키가 아니다. 비면 `Asia/Seoul` 이다 (compose · Dockerfile).
- 무작위 비밀값(JWT 키 · pepper · DB 비밀번호)은 `$` · 따옴표가 섞이지 않게 만든다. `openssl rand -base64` 출력은 해당 없다.

### 값 규칙 (이 문서와 파이프라인 검사가 기준)

| key | dev | prod | 검사 |
|-----|-----|------|------|
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` | 배포 환경과 같아야 한다 |
| `SERVICE_DISCOVERY_HOSTNAME` | `192.168.0.13` | 미니PC 사설 IP (미정) | dev 는 값 대조, prod 는 비어 있지 않음만 |
| `SERVICE_DISCOVERY_PORT` | `3761` | `4761` | 모든 잡이 값 대조 (내부 포트 = 호스트 포트) |
| 서비스 포트 키 (`API_GATEWAY_PORT` 등) | §4 표의 dev 포트 | §4 표의 prod 포트 | 그 잡의 `hostPorts` 와 대조 |
| `AUTH_SERVICE_APP_NAME` / `SURVEILLANCE_SERVICE_APP_NAME` | `auth-service` / `surveillance-service` | 같음 | compose 가 각 서비스의 `SPRING_APPLICATION_NAME` 으로 넘긴다 |
| `AUTH_DB_URL` | `jdbc:mysql://192.168.0.11:3306/sneezecast_auth?...` | 미정 | DB 이름은 §8 SQL 과 같아야 한다 |
| `SURVEILLANCE_DB_URL` · `BATCH_DB_URL` | `jdbc:mysql://192.168.0.11:3306/sneezecast_surveillance?...` | 미정 | batch 도 같은 DB 다 |
| `GATEWAY_TRUSTED_PROXIES` | `192.168.0.12` (nginx) | prod nginx 사설 IP (미정) | 앱이 기동 때 형식(IP · CIDR, 호스트명 거부)만 본다. 값이 틀려도 뜨지만 nginx 경유 요청이 모두 nginx IP 한 키를 나눠 써 auth IP 발송 상한이 전체에 걸린다 — §9 7번으로 확인 |

URL 옵션(`?` 뒤)은 Infra 키 표의 혼디가개 secret 과 같게 둔다.

## 7. 메모리 상한

compose 의 `{SVC}_MEM_LIMIT_DEV` / `_PROD` 로 컨테이너 상한을 둔다. JVM heap 은 Dockerfile 의
`-XX:MaxRAMPercentage=70.0` 이 이 상한을 기준으로 잡는다. 값을 바꾸려면 Vault 공통 경로에 키를 넣는다.

| 서비스 | dev 기본 | prod 기본 |
|--------|----------|-----------|
| service-discovery | 384m | 512m |
| api-gateway · auth · surveillance · batch | 512m | 768m |

`.13` 에 들어갈 양은 서비스당 실측 ~335MB × 5 + deploy agent ~150MB 로 약 1.8Gi(Infra README §7 추정)다.
batch 의 수동 잡 실행은 같은 상한 안에 두 번째 JVM 을 띄운다(compose 주석의 `MaxRAMPercentage=25`).

## 8. 첫 배포 전 서버 준비 체크리스트

> **주의 — 배포 파이프라인을 처음 들이는 PR(#10)은 머지 전에 `backend-*` 라벨을 모두 뗀다.**
> 그 PR 은 5개 모듈을 다 건드려 labeler 가 `backend-*` 5개를 붙인다. 그대로 머지하면 develop 빌드에서 5개 잡이
> 아래 준비(agent · Vault · DB)가 끝나기 전에 배포를 시도한다. 첫 배포는 이 체크리스트를 끝낸 뒤
> **`FORCE_DEPLOY=true` 로 discovery → gateway → auth → surveillance → batch 순서**로 잡을 하나씩 실행한다(7번).

순서대로 한다. 1 ~ 3 은 Infra 레포 문서의 절차를 따른다.

- [ ] **1. deploy agent 기동** — `.13` 에 `backend-dev2-agent`(라벨 `deploy-backend-dev2`)가 없으면 dev 잡이 노드를
      기다리며 멈춘다. Jenkins UI 에서 노드 생성(라벨 지정) → secret 복사 → `.13` 에서 `.env.deploy-agent.example` 을
      `.env.backend-dev2` 로 복사해 채움 → `sh install-jenkins-agent.sh .env.backend-dev2` (Infra `jenkins/README.md`).
      agent 에 `docker`, `docker compose`, `rsync`, `curl` 이 있어야 한다.
- [ ] **2. Vault** (Infra `vault/README.md` sneezecast 절)
  - [ ] `bootstrap-sneezecast.sh` 실행 → `jenkins-sneezecast` 정책 반영 확인
  - [ ] `rotate-approle-secret.sh jenkins-sneezecast` → `role_id` / `secret_id` 를 Jenkins credential 로 (3번)
  - [ ] dev 경로 5개 적재: `env`, `api-gateway`, `auth-service`, `surveillance-service`, `batch-service`.
        같은 키를 두 경로에 넣지 않는다. 포트 · discovery 값은 §6 "값 규칙" 대로 넣는다.
  - [ ] `JWT_ACCESS_KEY` · `JWT_REFRESH_KEY` · `REPORTER_KEY_PEPPER` 생성. **pepper 는 오프라인 사본을 한 부 둔다** (교체 · 분실 불가)
- [ ] **3. Jenkins**
  - [ ] Secret text credential `sneezecast-vault-role-id`, `sneezecast-vault-secret-id`
  - [ ] GitHub App(`github-app-followfollowme-jenkins`)이 `8llow8llowMe/sneezecast` 저장소에 설치돼 있는지 확인 (라벨 조회)
  - [ ] 멀티브랜치 잡 5개: `sneezecast-service-discovery`, `sneezecast-api-gateway`, `sneezecast-auth-service`,
        `sneezecast-surveillance-service`, `sneezecast-batch-service`. Branch source 는 GitHub App credential,
        Script Path 는 `Jenkinsfile-<service>`, 브랜치(`develop` · `main`)와 PR 을 discover, 웹훅 연결
  - [ ] 배포 lock `sneezecast-backend-deploy` — `lock` 스텝이 처음 쓸 때 만든다. 미리 만들 필요는 없다
- [ ] **4. MySQL** (dev MySQL `192.168.0.11`) — 아래 SQL. DB 이름은 `*_DB_URL` 과 한 글자도 다르면 안 된다
- [ ] **5. MinIO** (`192.168.0.12`) — 버킷 `sneezecast` 생성 (`mc mb <alias>/sneezecast`). 익명 읽기 정책 범위는
      프로필 이미지 업로드 기능 이슈에서 오브젝트 키 규칙과 함께 정한다
- [ ] **6. nginx** — Infra `nginx/conf.d/api-dev.sneezecast.conf` 적용(HTTPS 블록 주석 상태로 reload → 인증서 발급 → 주석 해제)
- [ ] **7. 첫 배포** — 각 잡을 `FORCE_DEPLOY=true` 로 하나씩, 앞 잡이 초록이 된 뒤 다음 잡을 실행한다
  1. `sneezecast-service-discovery` (다른 서비스가 등록할 대상)
  2. `sneezecast-api-gateway`
  3. `sneezecast-auth-service`
  4. `sneezecast-surveillance-service`
  5. `sneezecast-batch-service` (웹 트래픽 없음, 마지막. 적재 대상 테이블은 surveillance 가 만든다)

  이후 배포는 PR 라벨(§2)을 따른다. 여러 서비스를 함께 바꾼 PR 도 잡끼리는 lock 으로 한 번에 하나씩 돌지만
  순서는 보장하지 않으므로, discovery 를 바꾸는 PR 은 따로 머지하는 편이 낫다.
- [ ] **8. 배포 후 점검** (§9) — 게이트웨이 → Eureka 등록까지 확인

### MySQL DB · 계정

dev MySQL 은 다른 프로젝트와 함께 쓰는 인스턴스라 DB 이름에 `sneezecast_` 접두사를 붙인다(문서의 논리 이름은
`auth` · `surveillance` 그대로). `CREATE DATABASE` 에 `IF NOT EXISTS` 를 두지 않는다 — 같은 이름이 이미 있으면
남의 DB 를 조용히 쓰지 않고 에러로 멈춘다.

계정은 서비스마다 하나, 권한은 그 서비스가 쓰는 범위로만 준다 — 신원(auth)과 가명 증상(surveillance)을 한 계정으로
결합할 수 없게 하는 것이 요점이다(`architecture-guide.md` §6).

```sql
-- dev MySQL(192.168.0.11) 에 root 로 1회 실행. 비밀번호는 Vault 의 *_DB_PASSWORD 와 같은 값.
-- 이미 있으면 ERROR 1007 로 멈춘다. 그때는 그 DB 가 누구 것인지부터 확인한다.
CREATE DATABASE sneezecast_auth         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE sneezecast_surveillance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'sneezecast_auth'@'%'         IDENTIFIED BY '<AUTH_DB_PASSWORD>';
CREATE USER 'sneezecast_surveillance'@'%' IDENTIFIED BY '<SURVEILLANCE_DB_PASSWORD>';
CREATE USER 'sneezecast_batch'@'%'        IDENTIFIED BY '<BATCH_DB_PASSWORD>';

-- auth / surveillance: 자기 DB 만. dev 는 ddl-auto:update 라 테이블 · 컬럼 · 인덱스 생성 권한까지 준다.
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON sneezecast_auth.*         TO 'sneezecast_auth'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON sneezecast_surveillance.* TO 'sneezecast_surveillance'@'%';

-- batch: DB 전체가 아니라 Spring Batch 메타 테이블(BATCH_*)과 적재 대상 테이블만.
-- dev 는 앱이 BATCH_* 를 만든다(initialize-schema: always). 없는 테이블에 GRANT 하려면 CREATE 가 포함돼야 하고,
-- 메타 테이블끼리 FK 가 있어 부모 테이블(JOB_INSTANCE · JOB_EXECUTION · STEP_EXECUTION)에는 REFERENCES 도 필요하다.
GRANT SELECT, INSERT, UPDATE, CREATE, REFERENCES ON sneezecast_surveillance.BATCH_JOB_INSTANCE           TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE, REFERENCES ON sneezecast_surveillance.BATCH_JOB_EXECUTION          TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE             ON sneezecast_surveillance.BATCH_JOB_EXECUTION_PARAMS   TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE, REFERENCES ON sneezecast_surveillance.BATCH_STEP_EXECUTION         TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE             ON sneezecast_surveillance.BATCH_STEP_EXECUTION_CONTEXT TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE             ON sneezecast_surveillance.BATCH_JOB_EXECUTION_CONTEXT  TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE             ON sneezecast_surveillance.BATCH_STEP_EXECUTION_SEQ     TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE             ON sneezecast_surveillance.BATCH_JOB_EXECUTION_SEQ      TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE, CREATE             ON sneezecast_surveillance.BATCH_JOB_SEQ                TO 'sneezecast_batch'@'%';

-- 적재 대상(멱등 upsert). 구조의 정본은 surveillance 라 batch 에는 CREATE 를 주지 않는다.
-- 그래서 **surveillance-service 가 테이블을 만든 뒤에** 실행한다 (없는 테이블이면 ERROR 1146).
GRANT SELECT, INSERT, UPDATE ON sneezecast_surveillance.district                 TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE ON sneezecast_surveillance.official_surveillance    TO 'sneezecast_batch'@'%';
GRANT SELECT, INSERT, UPDATE ON sneezecast_surveillance.official_source_snapshot TO 'sneezecast_batch'@'%';
```

- 계정 호스트를 `'%'` 로 둔 것은 컨테이너 NAT 를 거친 접속 주소를 확정하지 않아서다. 확인되면 `'192.168.0.13'` 으로 좁힌다.
- 적재 잡이 행 삭제를 하게 되면 그 기능 이슈에서 해당 테이블에 `DELETE` 를 더한다.
- MySQL 은 대소문자를 구분해 테이블 이름을 비교한다(리눅스 기본값). `BATCH_*` 는 대문자 그대로 쓴다.
- **prod** 는 DDL 권한을 주지 않는다 — auth · surveillance 는 `SELECT, INSERT, UPDATE, DELETE` 만(`ddl-auto: none`),
  batch 는 위 표에서 `CREATE` · `REFERENCES` 를 뺀다. `BATCH_*` 는 DB 담당자가 spring-batch-core 의 `schema-mysql.sql` 을
  직접 적용한다. prod DB 위치는 아직 정하지 않았다.

## 9. 배포 후 점검

discovery(3761)와 게이트웨이(3000)는 사설망(`192.168.0.x`)에서, 루프백에만 열린 auth · surveillance · batch 는
**`.13` 에서** `127.0.0.1` 로 보거나 `docker exec` 로 컨테이너 안에서 본다.

```bash
# 1. Eureka 등록 (사설망) — API-GATEWAY, AUTH-SERVICE, SURVEILLANCE-SERVICE, BATCH-SERVICE 가 보여야 한다
curl -s http://192.168.0.13:3761/eureka/apps | grep -o '<name>[^<]*</name>'

# 2. 게이트웨이 health (사설망)
curl -s http://192.168.0.13:3000/actuator/health

# 3. auth 3081 · surveillance 3082 · batch 3080 health — .13 에 접속해서
curl -s http://127.0.0.1:3081/actuator/health
#    이미지에 curl 이 없으면 호스트에서 위처럼 보거나, 컨테이너 상태를 본다
docker inspect -f '{{.State.Status}} {{.RestartCount}}' sneezecast-auth-service-dev

# 4. 게이트웨이 → 업스트림 (라우트가 lb:// 로 Eureka 를 거친다. 503 이면 대상이 아직 등록 전이다)
curl -s -o /dev/null -w '%{http_code}\n' --get --data-urlencode 'query=역삼' http://192.168.0.13:3000/api/v1/districts

# 5. 공개 도메인 경유 (nginx + 인증서). /actuator 는 404 여야 한다
curl -s -o /dev/null -w '%{http_code}\n' https://api-dev.sneezecast.com/actuator/health

# 6. 루프백 publish 확인 — 사설망의 다른 호스트에서 서비스 포트가 닫혀 있어야 한다
curl -s -m 3 http://192.168.0.13:3081/actuator/health || echo "닫힘 (정상)"

# 7. 신뢰 프록시 — 공개 도메인으로 한 번 요청한 뒤 게이트웨이 접근 로그의 clientIp 가 실제 공인 IP 여야 한다.
#    192.168.0.12 · 172.x 가 찍히면 GATEWAY_TRUSTED_PROXIES 가 실제 접속 주소와 다르다(Docker NAT · userland-proxy 등)
docker logs --since 2m sneezecast-api-gateway-dev 2>&1 | grep -o 'clientIp=[^ ]*' | sort | uniq -c
#    사설망의 다른 호스트에서 위조 헤더를 보내면 clientIp 는 위조값이 아니라 그 호스트 IP 여야 한다
curl -s -o /dev/null -H 'X-Real-IP: 1.2.3.4' http://192.168.0.13:3000/api/v1/districts
```

4번은 200 이면 라우팅 · surveillance · DB 조회까지 된 것이다(행정동 적재 전이면 빈 배열). `query` 를 빼면 400 `DISTRICT_101` 이다. 게이트웨이 자체의 503 · 504 와 구분한다.
surveillance 기동 로그의 pepper 지문(SHA-256 앞 8자)을 첫 배포 때 기록해 두고, 이후 배포에서 바뀌지 않았는지 본다.

## 10. prod — 설정만 있고 검증하지 않았다

- `main` 머지 빌드는 prod 로 배포하려고 `deploy-backend-prod` agent 를 찾는다. **그 agent 가 아직 없다**(미니PC 미세팅).
  배포 스테이지는 lock 을 잡은 채 agent 를 기다리므로 **30분 상한**을 두었다 — 상한이 없으면 dev 배포까지 막힌다.
  상한에 걸리면 빌드는 실패(빨강)가 아니라 **ABORTED(회색)** 로 끝난다.
- prod 미니PC IP 가 정해지면 Vault prod 공통의 `SERVICE_DISCOVERY_HOSTNAME`, 공용 파이프라인의 `discoveryEndpoints()` prod 호스트,
  Infra nginx `api.sneezecast.conf` 를 함께 채운다.
- prod 미니PC 아키텍처는 미확인이다. 이미지(`ibm-semeru-runtimes:open-21-jre-jammy`)는 arm64 · amd64 공용이라 그대로 쓸 수 있다.
- prod 는 `ddl-auto: none`, `BATCH_*` 미생성, 스케줄 기본 꺼짐이다 — 스키마 · 메타 테이블 적용 절차가 먼저 필요하다.

## 11. 트러블슈팅

| 증상 | 원인 | 조치 |
|------|------|------|
| `변경 없음 - 생략` 으로 끝남 | 이번 push 가 그 서비스와 무관 | 정상 |
| `배포 대상 라벨 미지정` 으로 끝남 | PR 에 `backend-<svc>` 라벨이 없다 (`backend-core` 만 있는 경우 포함) | `FORCE_DEPLOY=true` 로 수동 실행 |
| UNSTABLE, 배포 생략 | GitHub App credential 로 라벨 조회 실패 | credential · App 설치 확인 |
| `같은 키가 Vault 두 경로에 모두 있습니다` | 공통과 서비스 경로에 같은 키 | 한 경로에서 지운다 (공통 키는 공통에만) |
| `서비스 전용 키가 공통 경로 ... 에 있습니다` | pepper · DB 계정이 공통 경로에 있다 | 주인 서비스 경로로 옮긴다 |
| `Vault 값에 $ 가 있습니다` / `따옴표로 시작합니다` / `공백 뒤 # 가 있습니다` | compose `--env-file` 이 값을 바꿀 형식 | 그 문자 없이 값을 다시 만든다 (비밀번호 · 키 재발급) |
| `Vault secret ... 을 읽지 못했습니다` | 서비스 경로를 적재하지 않았거나 정책 미반영 | 경로 적재, `bootstrap-sneezecast.sh` 재실행 |
| `필수 키가 없거나 비어 있습니다` | §6 필수 키 누락 | 메시지의 키를 해당 층에 넣는다 |
| `SPRING_PROFILES_ACTIVE=... 가 배포 환경(...)과 다릅니다` | prod secret 에 dev 등 | 공통 경로 값을 환경에 맞춘다 |
| `..._PORT=... 가 ... 호스트 포트(...)와 다릅니다` | Vault 포트 키에 내부 포트(8xxx 등)를 넣었다 | §4 표의 호스트 포트를 넣는다 |
| `SERVICE_DISCOVERY_PORT` / `SERVICE_DISCOVERY_HOSTNAME ... discovery ...와 다릅니다` | 공통 경로의 discovery 값이 규칙과 다르다 (컨테이너명 · 8761 등) | §6 "값 규칙" 대로 `192.168.0.13` · `3761` |
| Eureka 에 아무것도 안 뜬다 | discovery 미기동 | discovery 먼저 배포 |
| 게이트웨이 503 | 대상 서비스 미기동 · Eureka 등록 전 · `*_SERVICE_APP_NAME` 불일치 | Eureka 앱 목록과 등록명 대조 |
| 모든 토큰이 401 | 서비스마다 `JWT_ACCESS_KEY` 가 다르다 (서비스 경로에 복사본) | 공통 경로 한 항목만 둔다 |
| `컨테이너가 기동 직후 안정되지 않았습니다` | 45초 안에 죽었거나 재시작 | 뒤따르는 `docker logs` 확인 (pepper 32자 미만, JWT 키 64바이트 미만 등 기동 검사) |
| 기동 직후 `Unknown database` | DB 가 없거나 `*_DB_URL` 과 이름이 다르다 | §8 SQL (`sneezecast_auth` · `sneezecast_surveillance`) |
| batch 적재 잡이 `command denied` | 적재 대상 GRANT 를 surveillance 테이블 생성 전에 못 했다 | §8 SQL 의 적재 대상 GRANT 를 다시 실행 |
| 사설망에서 auth · surveillance · batch 포트가 안 열린다 | 루프백에만 publish 한다 (의도) | `.13` 에서 `127.0.0.1` 로 본다 (§9) |
| prod 배포 스테이지가 30분 뒤 ABORTED | `deploy-backend-prod` agent 없음 | §10 |
