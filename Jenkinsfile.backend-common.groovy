// sneezecast 백엔드 공용 배포 파이프라인.
//
// 혼디가개(hondigagae)의 같은 파일에서 이식했다. 게이트 규칙은 그대로다.
//   - PR 빌드는 CI(빌드/테스트)만 수행하고 배포하지 않는다.
//     배포는 develop/main 에 **머지된 시점**의 브랜치 빌드에서만 일어난다. (develop -> dev, main -> prod)
//   - 배포 대상은 PR 라벨 `backend-{serviceName}` 로 지정한다. 라벨이 없으면 배포하지 않는다(fail-closed).
//   - 라벨을 빠뜨리고 머지한 커밋은 FORCE_DEPLOY 로 수동 배포한다.
//   - core 모듈이나 gradle 루트 설정이 바뀌면 전 서비스가 CI 를 돈다.
//     security-core 의 claim 규약처럼 여러 서비스가 함께 쓰는 코드가 있어 부분 빌드는 위험하다.
//
// sneezecast 는 같은 인프라(Jenkins·Vault·nginx)를 공유하되 아래가 다르다.
//   - Vault:      두 층을 읽어 합친다 (아래 "Vault secret 두 층" 참고)
//                   kv/sneezecast/backend/{env}/env            공통
//                   kv/sneezecast/backend/{env}/{serviceName}  서비스 전용 (service-discovery 는 없음)
//   - Credential: sneezecast-vault-role-id / sneezecast-vault-secret-id
//   - 포트 대역:  dev 3xxx / prod 4xxx. 컨테이너 내부 포트 = 호스트 포트 (Jenkinsfile 의 hostPorts 로 검사)
//   - 배포 경로:  $HOME/deploy/sneezecast/backend/{group}/{service}
//   - PR 빌드는 Vault 를 읽지 않는다. 빌드·테스트에 비밀값이 필요 없고, 배포하지 않는 빌드에 비밀을 꺼낼 이유가 없다.
//
// Vault secret 두 층 (경로 · 층 구분은 Infra vault/README.md sneezecast 절, 포트 · discovery 값 규칙은
// backend/docs/deploy-guide.md 와 이 파일의 검사가 기준이다)
//   - 공통 키(JWT_ACCESS_KEY, REDIS_*, SERVICE_DISCOVERY_* ...)는 공통 경로 한 곳에만 둔다.
//     서비스 경로에 복사본이 있으면 "길이는 맞고 값만 다른 키" 가 기동 검사를 통과해 조용히 장애가 된다.
//   - **같은 키가 두 경로에 다 있으면 덮어쓰지 않고 배포를 실패시킨다.** 어느 값이 이기는지에 기대지 않는다.
//   - **각 잡은 공통 + 자기 서비스 경로만 읽는다.** 서비스 경로는 config.serviceName 으로만 조립하고
//     파라미터로 바꿀 수 없다. Vault 정책은 backend/* 전체를 읽게 열려 있어서, 격리는 파이프라인의 몫이다.
//     (REPORTER_KEY_PEPPER 가 auth 배포 디렉터리의 .env.runtime 에 들어가면 회원과 가명 보고를 이을 수 있다)
//
// 잡 정의는 Jenkinsfile-{service} 가 이 파일을 load 해서 run(config) 를 부른다.

Map<String, String> resolveGitContext() {
    String requestedTargetBranch = params.TARGET_BRANCH?.trim()
    String requestedPrSha = params.PR_SHA?.trim()
    String requestedPrNumber = params.PR_NUMBER?.trim()

    String effectiveTargetBranch = requestedTargetBranch
    if (!effectiveTargetBranch) {
        effectiveTargetBranch = env.CHANGE_TARGET?.trim() ?: env.BRANCH_NAME
    }

    String deployEnv = 'none'
    if (effectiveTargetBranch == 'main') {
        deployEnv = 'prod'
    } else if (effectiveTargetBranch == 'develop') {
        deployEnv = 'dev'
    }

    return [
        requestedTargetBranch: requestedTargetBranch ?: '',
        effectiveTargetBranch: effectiveTargetBranch ?: '',
        requestedPrSha      : requestedPrSha ?: '',
        requestedPrNumber   : requestedPrNumber ?: '',
        deployEnv           : deployEnv,
        isPullRequest       : env.CHANGE_ID?.trim() ? 'true' : 'false'
    ]
}

void checkoutSource() {
    if (params.PR_SHA?.trim()) {
        echo "요청한 PR 커밋 SHA를 체크아웃합니다: ${params.PR_SHA}"
        checkout([
            $class: 'GitSCM',
            branches: [[name: params.PR_SHA.trim()]],
            userRemoteConfigs: scm.userRemoteConfigs,
            extensions: [
                [$class: 'SubmoduleOption', recursiveSubmodules: true, parentCredentials: true]
            ]
        ])
        return
    }

    echo '멀티브랜치 파이프라인 기본 SCM 컨텍스트로 체크아웃합니다.'
    checkout([
        $class: 'GitSCM',
        branches: scm.branches,
        userRemoteConfigs: scm.userRemoteConfigs,
        extensions: [
            [$class: 'SubmoduleOption', recursiveSubmodules: true, parentCredentials: true]
        ]
    ])
}

// 프로젝트 식별자와 Vault 루트는 상수다. 혼디가개의 PROJECT_SLUG · VAULT_SECRET_ROOT · VAULT_SECRET_PATH
// 파라미터를 두지 않는다 — 수동 실행에서 루트를 바꾸면 "공통 + 자기 서비스 경로만 읽는다" 를 우회할 수 있다.
String projectSlug() {
    return 'sneezecast'
}

String resolveVaultSecretRoot() {
    return "kv/${projectSlug()}/backend"
}

// discovery 가 실제로 듣는 주소. 클라이언트 잡(gateway · auth · surveillance · batch)의 Vault 값과 대조한다.
// 포트는 Jenkinsfile-service-discovery 의 hostPorts 와 같은 값이다 (하네스가 둘이 같은지 본다).
// prod 호스트(미니PC)는 IP 가 정해지지 않아 비워 둔다 — 그때는 필수 키 검사(비어 있지 않음)만 걸린다.
Map<String, Map<String, String>> discoveryEndpoints() {
    return [
        dev : [host: '192.168.0.13', port: '3761'],
        prod: [host: '', port: '4761']
    ]
}

// 서비스 전용 secret 을 읽는 잡인지. 키가 아예 없으면 실패시킨다.
// 빠뜨린 것을 "서비스 경로 없음" 으로 해석하면 surveillance 가 pepper 없이 배포 단계까지 가서야 죽는다.
boolean readsServiceSecret(Map<String, Object> config) {
    if (!config.containsKey('readServiceSecret')) {
        error "Jenkinsfile-${config.serviceName} 의 config 에 readServiceSecret(true|false) 이 없습니다. 서비스 전용 Vault 경로를 읽을지 명시하세요."
    }
    return config.readServiceSecret == true
}

// 이 잡이 읽을 Vault 경로를 돌려준다. service 는 서비스 경로가 없는 잡(service-discovery)이면 null 이다.
// 서비스 경로는 config.serviceName 으로만 만든다 — 다른 서비스 경로를 가리킬 수단을 두지 않는다.
Map<String, Object> resolveVaultSpecs(Map<String, Object> config, Map<String, String> ctx) {
    String vaultSecretRoot = resolveVaultSecretRoot()
    Integer engineVersion = (params.VAULT_ENGINE_VERSION ?: '2') as Integer

    String commonPath = "${vaultSecretRoot}/${ctx.deployEnv}/env"
    Map<String, Object> common = [
        path         : commonPath,
        // KV v2는 mount path와 secret path 사이에 /data/가 필요합니다.
        apiPath      : resolveVaultApiPath(commonPath, engineVersion),
        engineVersion: engineVersion
    ]

    Map<String, Object> service = null
    if (readsServiceSecret(config)) {
        String servicePath = "${vaultSecretRoot}/${ctx.deployEnv}/${config.serviceName}"
        service = [
            path         : servicePath,
            apiPath      : resolveVaultApiPath(servicePath, engineVersion),
            engineVersion: engineVersion
        ]
    }

    return [common: common, service: service]
}

String describeVaultSpecs(Map<String, Object> specs) {
    String servicePath = specs.service ? specs.service.path : '(없음 — 공통만 읽는다)'
    return "공통 ${specs.common.path} / 서비스 ${servicePath}"
}

String resolveDeployAgentLabel(Map<String, Object> config, Map<String, String> ctx) {
    Map<String, String> deployAgentLabels = config.deployAgentLabels ?: [:]
    String deployAgentLabel = deployAgentLabels[ctx.deployEnv] ?: config.deployAgentLabel

    if (!deployAgentLabel?.trim()) {
        error "배포 agent label을 찾을 수 없습니다. deployEnv=${ctx.deployEnv}"
    }

    return deployAgentLabel.trim()
}

boolean shouldDeployToEnvironment(Map<String, String> ctx) {
    if (params.SKIP_DEPLOY || !(ctx.deployEnv in ['dev', 'prod'])) {
        return false
    }

    // PR 빌드는 CI(빌드/테스트)만 수행하고 배포하지 않습니다.
    // dev 환경은 develop 브랜치 머지 빌드만 배포해 "dev = develop 미러"를 보장합니다.
    // (PR 도 dev 를 배포하게 두면 develop 머지 빌드와 같은 dev 환경을 두고 경합하여
    //  머지되지 않은 PR 배포가 develop 빌드로 덮이는 혼선이 생깁니다.)
    if (ctx.isPullRequest == 'true') {
        return false
    }

    return true
}

// 이번 빌드에서 변경된 파일 목록을 계산합니다. 판단이 불가능하면 null을 반환해 전체 빌드로 진행합니다(fail-open).
List<String> resolveChangedFiles(Map<String, String> ctx) {
    try {
        String diffOutput
        if (ctx.isPullRequest == 'true' && env.CHANGE_TARGET?.trim()) {
            // PR 빌드: 대상 브랜치와의 merge-base 기준으로 PR이 실제 건드린 파일만 계산합니다.
            //
            // 여기서 git fetch 를 직접 실행하면 안 됩니다. 파이프라인 sh 에는 checkout 단계의
            // credential(GIT_ASKPASS)이 없어 사설 레포 fetch 가 exit 128("could not read
            // Username")로 죽고, fail-open 규칙에 따라 무관한 PR 에서도 전체 CI 가 돕니다.
            // 멀티브랜치 PR 잡은 checkout 이 대상 브랜치를 refspec 에 포함해 방금
            // credential 로 받아뒀으므로(origin/<target>), 그 ref 를 그대로 씁니다.
            String target = env.CHANGE_TARGET.trim()
            int hasTargetRef = sh(returnStatus: true, script: "git rev-parse --verify --quiet refs/remotes/origin/${target}")
            if (hasTargetRef != 0) {
                echo "origin/${target} ref 가 워크스페이스에 없어 변경 파일을 판단할 수 없습니다. 전체 빌드로 진행합니다."
                return null
            }
            diffOutput = sh(returnStdout: true, script: "git diff --name-only origin/${target}...HEAD").trim()
        } else if (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim()) {
            // 브랜치 빌드: 이 잡의 마지막 성공 빌드 이후 변경분만 계산합니다.
            String previous = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT.trim()
            int exists = sh(returnStatus: true, script: "git cat-file -e ${previous}^{commit}")
            if (exists != 0) {
                echo "이전 성공 커밋(${previous})을 찾을 수 없어 전체 빌드로 진행합니다."
                return null
            }
            diffOutput = sh(returnStdout: true, script: "git diff --name-only ${previous} HEAD").trim()
        } else {
            echo '변경 파일 기준 커밋을 판단할 수 없어 전체 빌드로 진행합니다. (첫 빌드 등)'
            return null
        }
        return diffOutput ? diffOutput.split('\n').collect { it.trim() }.findAll { it } : []
    } catch (Exception e) {
        echo "변경 파일 계산에 실패해 전체 빌드로 진행합니다: ${e.message}"
        return null
    }
}

// 변경 파일이 이 서비스 잡에 주는 영향 "범위" 를 판단합니다.
//   'own'      — 자기 서비스 경로 또는 공용 코드(core 모듈, gradle 루트 설정) 변경,
//                또는 판단 불가(fail-open). CI 를 반드시 돕니다. 공용 코드를 'own' 으로 두는
//                이유: core 변경이 다른 서비스의 컴파일을 깨는지는 PR 단계에서 잡아야 합니다.
//   'pipeline' — 이 잡의 파이프라인 정의 파일만 변경. PR 라벨 스코프로 CI 생략이 가능합니다.
//   'none'     — 이 잡과 무관. 빌드/배포를 건너뜁니다.
String resolveAffectedScope(Map<String, Object> config, List<String> changedFiles) {
    if (changedFiles == null) {
        return 'own'
    }
    if (changedFiles.isEmpty()) {
        echo '변경 파일이 없어 빌드/배포를 건너뜁니다.'
        return 'none'
    }

    String servicePrefix = "${config.fsPath}/"
    List<String> sharedCodePrefixes = [
        'backend/core/',
        'backend/build.gradle',
        'backend/settings.gradle',
        'backend/gradle'
    ]
    // 파이프라인 정의 변경은 '이 잡의' 파일에만 반응한다.
    // 'Jenkinsfile' 프리픽스 하나로 두면 다른 서비스의 Jenkinsfile-* 변경에도
    // 5개 잡 전체가 gradle CI 를 돌게 된다.
    List<String> pipelinePrefixes = [
        'Jenkinsfile.backend-common.groovy',
        "Jenkinsfile-${config.serviceName}".toString()
    ]

    List<String> ownMatched = changedFiles.findAll { path ->
        path.startsWith(servicePrefix) || sharedCodePrefixes.any { prefix -> path.startsWith(prefix) }
    }
    List<String> pipelineMatched = changedFiles.findAll { path ->
        pipelinePrefixes.any { prefix -> path.startsWith(prefix) }
    }

    if (ownMatched) {
        echo "이 서비스에 영향 있는 변경 ${ownMatched.size()}건: ${ownMatched.take(10).join(', ')}${ownMatched.size() > 10 ? ' ...' : ''}"
        return 'own'
    }
    if (pipelineMatched) {
        echo "파이프라인 정의 변경 ${pipelineMatched.size()}건: ${pipelineMatched.join(', ')}"
        return 'pipeline'
    }

    echo "변경 파일 ${changedFiles.size()}건 중 ${config.serviceName} 관련 변경이 없어 빌드/배포를 건너뜁니다."
    return 'none'
}

// origin remote URL에서 GitHub owner/repo 슬러그를 뽑아냅니다. 판단 불가 시 빈 문자열을 반환합니다.
// (정규식 Matcher는 CPS 직렬화 대상이 아니라 파이프라인에서 예외를 유발하므로 문자열 연산만 사용합니다.)
String resolveRepositorySlug() {
    String url = sh(returnStdout: true, script: 'git config --get remote.origin.url').trim()
    if (!url) {
        return ''
    }

    String slug = url.endsWith('.git') ? url.substring(0, url.length() - 4) : url
    int hostIndex = slug.indexOf('github.com')
    if (hostIndex < 0) {
        return ''
    }

    slug = slug.substring(hostIndex + 'github.com'.length())
    while (slug.startsWith(':') || slug.startsWith('/')) {
        slug = slug.substring(1)
    }

    return slug.contains('/') ? slug : ''
}

// GitHub REST API를 GET 호출합니다. GitHub App credential의 installation token을 사용합니다.
String githubApiGet(String apiPath, String credentialId) {
    String response = ''

    withCredentials([usernamePassword(
        credentialsId: credentialId,
        usernameVariable: 'GITHUB_API_USER',
        passwordVariable: 'GITHUB_API_TOKEN'
    )]) {
        withEnv(["GITHUB_API_URL=https://api.github.com/${apiPath}"]) {
            response = sh(
                returnStdout: true,
                script: '''#!/usr/bin/env bash
set +x
set -euo pipefail

# 토큰을 curl argv 에 싣지 않는다 (같은 호스트의 ps 로 보인다). printf 는 셸 내장이라 프로세스 인자로 남지 않는다.
printf 'header = "Authorization: Bearer %s"\\n' "$GITHUB_API_TOKEN" | curl --config - \
  --fail --silent --show-error \
  --connect-timeout 10 --max-time 30 \
  --header "Accept: application/vnd.github+json" \
  --header "X-GitHub-Api-Version: 2022-11-28" \
  "$GITHUB_API_URL"
'''
            ).trim()
        }
    }

    return response
}

// 이 빌드와 연결된 PR의 라벨 목록을 조회합니다.
//
// `Github label filter` 플러그인은 PR discovery 단계만 제한하므로, 머지 후 브랜치 빌드에서는
// 이렇게 직접 라벨을 확인해야 합니다. 실제 호출은 배포 대상 브랜치 빌드에서만 일어나고
// (PR 빌드는 어차피 배포하지 않아 호출부에서 걸러집니다), 아래 CHANGE_ID 분기는
// 이 함수만 놓고 봐도 어느 빌드에서든 올바른 PR을 가리키도록 남겨둔 경로입니다.
//
// 반환: [resolved: 'true'|'false', reason: '사유 코드', labels: List<String>]
//   resolved='true'  — 라벨 목록을 확정했습니다. (라벨이 0개인 것도 확정입니다)
//   resolved='false' — 조회 자체가 불가능했습니다. reason으로 원인을 구분합니다.
Map<String, Object> resolveDeployLabelContext(Map<String, String> ctx) {
    String credentialId = params.GITHUB_APP_CREDENTIAL_ID?.trim()
    if (!credentialId) {
        return [resolved: 'false', reason: 'NO_CREDENTIAL', labels: []]
    }

    try {
        String slug = resolveRepositorySlug()
        if (!slug) {
            return [resolved: 'false', reason: 'NO_REPOSITORY_SLUG', labels: []]
        }

        def pullRequest = null
        if (ctx.isPullRequest == 'true' && env.CHANGE_ID?.trim()) {
            pullRequest = readJSON text: githubApiGet("repos/${slug}/pulls/${env.CHANGE_ID.trim()}", credentialId)
        } else {
            String headSha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            def associated = readJSON text: githubApiGet("repos/${slug}/commits/${headSha}/pulls", credentialId)
            if (!associated || associated.isEmpty()) {
                // PR 없이 브랜치에 직접 push한 경우입니다. 라벨이 지정되지 않은 것과 같게 취급합니다.
                echo "커밋 ${headSha.take(8)}에 연결된 PR이 없습니다. (브랜치 직접 push)"
                return [resolved: 'true', reason: 'NO_PULL_REQUEST', labels: []]
            }
            // 머지 커밋이면 merge_commit_sha가 일치하는 PR이 정확한 출처입니다.
            associated.each { candidate ->
                if (pullRequest == null && candidate?.merge_commit_sha?.toString() == headSha) {
                    pullRequest = candidate
                }
            }
            if (pullRequest == null) {
                pullRequest = associated[0]
            }
        }

        if (pullRequest == null) {
            return [resolved: 'false', reason: 'PULL_REQUEST_PARSE_FAILED', labels: []]
        }

        List<String> labels = []
        pullRequest.labels?.each { label ->
            String name = label?.name?.toString()
            if (name) {
                labels.add(name)
            }
        }

        echo "PR #${pullRequest.number} 라벨: ${labels ? labels.join(', ') : '없음'}"
        return [resolved: 'true', reason: '', labels: labels]
    } catch (Exception e) {
        echo "PR 라벨 조회에 실패했습니다: ${e.message}"
        return [resolved: 'false', reason: 'API_ERROR', labels: []]
    }
}

// 라벨을 기준으로 이 서비스를 배포할지 판단합니다.
// PR에 `backend-{serviceName}` 라벨이 지정된 서비스만 배포합니다.
//
// 라벨이 하나도 없으면 어떤 서비스도 배포하지 않습니다(fail-closed).
// 배포는 의도적으로 지정한 대상만 나가야 하므로, 라벨이 없을 때 전체 배포로 넓히지 않습니다.
// 따라서 배포하려면 PR에 라벨을 반드시 지정해야 합니다.
// (`backend-core` 는 배포 라벨이 아니다 — 서비스 이름이 아니라 어느 잡과도 일치하지 않는다. .github/labeler.yml 참고)
boolean isServiceDeployAllowedByLabels(Map<String, Object> config, Map<String, Object> labelContext) {
    String prefix = params.DEPLOY_LABEL_PREFIX?.trim() ?: 'backend-'

    if (labelContext.resolved != 'true') {
        // 설정/통신 문제로 라벨을 확인하지 못한 상태입니다.
        // 배포는 막되, credential 오설정으로 배포가 영구히 멈춘 것을 알아챌 수 있도록 UNSTABLE로 표시합니다.
        echo "PR 라벨을 확인할 수 없어 배포하지 않습니다. reason=${labelContext.reason}"
        currentBuild.result = 'UNSTABLE'
        currentBuild.description = "라벨 확인 실패(${labelContext.reason}) - 배포 생략"
        return false
    }

    List<String> serviceLabels = (labelContext.labels as List<String>).findAll { it.startsWith(prefix) }

    if (!serviceLabels) {
        echo "배포 대상 라벨(${prefix}*)이 지정되지 않아 배포하지 않습니다. 배포하려면 PR에 ${prefix}${config.serviceName} 라벨을 붙여주세요."
        return false
    }

    String expected = "${prefix}${config.serviceName}"
    if (serviceLabels.contains(expected)) {
        echo "라벨 ${expected}이 지정되어 배포 대상입니다."
        return true
    }

    echo "배포 대상 라벨: ${serviceLabels.join(', ')} — ${expected}이 없어 배포를 생략합니다."
    return false
}

String resolveVaultApiPath(String vaultSecretPath, Integer engineVersion) {
    if (engineVersion != 2) {
        return vaultSecretPath
    }

    List<String> parts = vaultSecretPath.tokenize('/')
    if (parts.size() < 2) {
        error "Vault KV v2 path must include mount and secret path: ${vaultSecretPath}"
    }

    String mountPath = parts.first()
    String secretPath = parts.drop(1).join('/')
    return "${mountPath}/data/${secretPath}"
}

String resolveVaultAddr() {
    String vaultAddr = params.VAULT_ADDR?.trim()
    if (!vaultAddr) {
        error 'VAULT_ADDR is required.'
    }
    return vaultAddr.replaceAll('/+$', '')
}

// AppRole 로 로그인해 client_token 을 돌려준다. 한 번 로그인한 토큰으로 두 경로를 읽는다.
String loginToVault(String vaultAddr) {
    String roleIdCredentialId = params.VAULT_ROLE_ID_CREDENTIAL_ID?.trim()
    String secretIdCredentialId = params.VAULT_SECRET_ID_CREDENTIAL_ID?.trim()
    String authPath = params.VAULT_AUTH_PATH?.trim() ?: 'approle'

    if (!roleIdCredentialId || !secretIdCredentialId) {
        error 'VAULT_ROLE_ID_CREDENTIAL_ID and VAULT_SECRET_ID_CREDENTIAL_ID are required.'
    }

    String loginResponse = ''
    withCredentials([
        string(credentialsId: roleIdCredentialId, variable: 'VAULT_ROLE_ID'),
        string(credentialsId: secretIdCredentialId, variable: 'VAULT_SECRET_ID')
    ]) {
        withEnv([
            "VAULT_ADDR=${vaultAddr}",
            "VAULT_AUTH_PATH=${authPath}"
        ]) {
            loginResponse = sh(
                returnStdout: true,
                script: '''#!/usr/bin/env bash
set +x
set -euo pipefail

# role_id · secret_id 를 curl argv 에 싣지 않고 stdin 으로 넘긴다 (같은 호스트의 ps 로 보인다).
# printf 는 셸 내장이라 프로세스 인자로 남지 않는다.
printf '{"role_id":"%s","secret_id":"%s"}' "$VAULT_ROLE_ID" "$VAULT_SECRET_ID" | curl \
  --fail --silent --show-error \
  --connect-timeout 10 --max-time 30 \
  --request POST \
  --data @- \
  "$VAULT_ADDR/v1/auth/$VAULT_AUTH_PATH/login"
'''
            ).trim()
        }
    }

    // Vault 로그인 응답에서 이후 조회에 사용할 client_token만 꺼냅니다.
    def loginPayload = readJSON text: loginResponse
    String clientToken = loginPayload?.auth?.client_token?.toString()
    if (!clientToken) {
        error 'Vault AppRole login did not return client_token.'
    }
    return clientToken
}

// secret 경로 하나를 키별 평면 맵으로 읽는다.
// 경로가 없거나(404) 비어 있으면 실패시킨다. 서비스 경로를 읽기로 한 잡에서 경로가 없다는 것은
// 적재를 빠뜨린 것이지 "공통만 쓰면 된다" 가 아니다 — 공통만 쓰는 잡은 config 로 명시한다.
Map<String, String> readVaultSecretValues(String vaultAddr, String clientToken, Map<String, Object> vaultSpec) {
    String secretResponse = ''
    // Jenkinsfile에 key 목록을 두지 않고 secret path 전체를 읽습니다.
    withEnv([
        "VAULT_ADDR=${vaultAddr}",
        "VAULT_CLIENT_TOKEN=${clientToken}",
        "VAULT_API_PATH=${vaultSpec.apiPath}"
    ]) {
        try {
            secretResponse = sh(
                returnStdout: true,
                script: '''#!/usr/bin/env bash
set +x
set -euo pipefail

# 토큰을 curl argv 에 싣지 않고 stdin 설정(--config -)으로 넘긴다.
printf 'header = "X-Vault-Token: %s"\\n' "$VAULT_CLIENT_TOKEN" | curl --config - \
  --fail --silent --show-error \
  --connect-timeout 10 --max-time 30 \
  "$VAULT_ADDR/v1/$VAULT_API_PATH"
'''
            ).trim()
        } catch (hudson.AbortException e) {
            error "Vault secret ${vaultSpec.path} 을 읽지 못했습니다 (경로가 없거나 권한이 없습니다). 적재 여부와 jenkins-sneezecast 정책을 확인하세요."
        }
    }

    // KV v2 응답은 data.data 아래에 실제 환경변수 key-value가 들어 있습니다.
    def secretPayload = readJSON text: secretResponse
    def rawSecretValues = [:]
    if (vaultSpec.engineVersion == 2) {
        rawSecretValues = secretPayload?.data?.data as Map
    } else {
        rawSecretValues = secretPayload?.data as Map
    }

    if (!rawSecretValues) {
        error "Vault secret has no key-value data: ${vaultSpec.path}"
    }

    return toEnvFileValues(rawSecretValues as Map, vaultSpec.path as String)
}

// Vault 응답을 .env.runtime 에 그대로 쓸 수 있는 KEY=value 맵으로 바꾼다.
// docker compose 의 --env-file 은 따옴표 없는 값을 그대로 두지 않는다 — 값 안의 $VAR 를 치환하고,
// 앞뒤 따옴표를 벗기고, 공백 뒤 # 를 주석으로 잘라낸다. 그러면 pepper 같은 값이 오류 없이 다른 값으로 바뀌어
// 기동 검사(길이)까지 통과할 수 있다. 그래서 그렇게 해석될 값은 쓰지 않고 실패시킨다.
// 메시지에는 값이 아니라 키 이름과 경로만 남긴다.
Map<String, String> toEnvFileValues(Map rawSecretValues, String vaultPath) {
    Map<String, String> secretValues = [:]
    rawSecretValues.each { key, value ->
        String envKey = key.toString()
        if (!(envKey ==~ /[A-Za-z_][A-Za-z0-9_]*/)) {
            error "Vault key is not a valid env var name: ${envKey} (${vaultPath})"
        }

        String envValue = value == null ? '' : value.toString()
        if (envValue.contains('\n') || envValue.contains('\r')) {
            error "Vault 값에 줄바꿈이 있습니다: ${envKey} (${vaultPath}). .env 한 줄로 쓸 수 없습니다."
        }
        if (envValue.contains('$')) {
            error "Vault 값에 \$ 가 있습니다: ${envKey} (${vaultPath}). compose 가 변수로 치환해 값이 바뀝니다. \$ 없는 값으로 다시 만드세요."
        }
        if (envValue.startsWith('"') || envValue.startsWith("'")) {
            error "Vault 값이 따옴표로 시작합니다: ${envKey} (${vaultPath}). compose 가 따옴표를 벗겨 값이 바뀝니다. 따옴표 없이 넣으세요."
        }
        if (envValue.contains(' #') || envValue.contains('\t#')) {
            error "Vault 값에 공백 뒤 # 가 있습니다: ${envKey} (${vaultPath}). compose 가 그 뒤를 주석으로 잘라냅니다."
        }

        secretValues[envKey] = envValue
    }
    return secretValues
}

// 공통 + 서비스 secret 을 합친다. 같은 키가 양쪽에 있으면 덮어쓰지 않고 실패시킨다.
// 값은 로그에 남기지 않고 키 이름만 남긴다.
Map<String, String> mergeVaultSecretValues(Map<String, Object> specs, Map<String, String> commonValues, Map<String, String> serviceValues) {
    List<String> duplicatedKeys = serviceValues.keySet().findAll { key -> commonValues.containsKey(key) }.sort()
    if (duplicatedKeys) {
        error "같은 키가 Vault 두 경로에 모두 있습니다: ${duplicatedKeys.join(', ')} " +
            "(${specs.common.path} / ${specs.service?.path}). 한 경로에서 지우세요 — 어느 값이 이기는지에 기대지 않습니다."
    }

    Map<String, String> merged = [:]
    merged.putAll(commonValues)
    merged.putAll(serviceValues)
    return merged
}

// 한 서비스만 가져야 하는 키와 그 주인. 개인정보 경계 때문이다.
//   - REPORTER_KEY_PEPPER 와 auth DB 계정이 한 .env.runtime 에 같이 있으면 그 파일 하나로
//     회원(memberId)과 가명 보고(reporter_key)를 이을 수 있다 (backend/docs/architecture-guide.md §6).
//   - DB 계정 세 개(auth · surveillance · batch)를 나눈 것도 같은 경계다.
// 이 키가 공통 경로나 주인이 아닌 서비스 경로에 있으면 실패시킨다.
Map<String, String> serviceOnlyKeyOwners() {
    return [
        'REPORTER_KEY_PEPPER'     : 'surveillance-service',
        'AUTH_DB_URL'             : 'auth-service',
        'AUTH_DB_USERNAME'        : 'auth-service',
        'AUTH_DB_PASSWORD'        : 'auth-service',
        'SURVEILLANCE_DB_URL'     : 'surveillance-service',
        'SURVEILLANCE_DB_USERNAME': 'surveillance-service',
        'SURVEILLANCE_DB_PASSWORD': 'surveillance-service',
        'BATCH_DB_URL'            : 'batch-service',
        'BATCH_DB_USERNAME'       : 'batch-service',
        'BATCH_DB_PASSWORD'       : 'batch-service'
    ]
}

void validateServiceOnlyKeys(Map<String, Object> config, Map<String, Object> specs, Map<String, String> commonValues, Map<String, String> serviceValues) {
    Map<String, String> owners = serviceOnlyKeyOwners()

    List<String> leakedToCommon = commonValues.keySet().findAll { key -> owners.containsKey(key) }.sort()
    if (leakedToCommon) {
        error "서비스 전용 키가 공통 경로 ${specs.common.path} 에 있습니다: " +
            leakedToCommon.collect { key -> "${key}(→ ${owners[key]})" }.join(', ') +
            '. 공통 경로는 모든 잡이 읽으므로 주인 서비스 경로로 옮기세요.'
    }

    List<String> foreignKeys = serviceValues.keySet().findAll { key ->
        owners.containsKey(key) && owners[key] != config.serviceName
    }.sort()
    if (foreignKeys) {
        error "다른 서비스 전용 키가 ${specs.service?.path} 에 있습니다: " +
            foreignKeys.collect { key -> "${key}(→ ${owners[key]})" }.join(', ')
    }
}

// 배포 전에 반드시 채워져 있어야 하는 키를 검사한다.
//   - requiredEnvKeys: Jenkinsfile-{service} 가 선언한 필수 키 (yml 에 기본값이 없는 자리표시자)
//   - SPRING_PROFILES_ACTIVE 는 모든 잡이 요구하고, 배포 환경과 같은 값이어야 한다.
//     prod secret 에 dev 가 남아 있으면 운영이 ddl-auto:update 로 떠서 배포마다 스키마가 바뀐다.
//   - portEnvKey 의 값은 hostPorts[env] 와 같아야 한다 (컨테이너 내부 포트 = 호스트 포트).
//     Eureka 클라이언트는 사설 IP + SERVICE_DISCOVERY_PORT 로 discovery 에 붙으므로 두 값이 같아야 하고,
//     다른 서비스도 같은 규칙으로 맞춰 두면 Eureka 에 등록되는 포트가 곧 호스트 포트가 된다.
void validateDeployEnvValues(Map<String, Object> config, Map<String, String> ctx, Map<String, Object> specs, Map<String, String> envValues) {
    List<String> requiredKeys = ['SPRING_PROFILES_ACTIVE']
    requiredKeys.addAll((config.requiredEnvKeys ?: []) as List<String>)
    if (config.portEnvKey) {
        requiredKeys.add(config.portEnvKey as String)
    }
    requiredKeys = requiredKeys.unique()

    List<String> missingKeys = requiredKeys.findAll { key -> !envValues[key]?.trim() }
    if (missingKeys) {
        error "Vault secret(${describeVaultSpecs(specs)})에 필수 키가 없거나 비어 있습니다: ${missingKeys.join(', ')}"
    }

    String activeProfile = envValues['SPRING_PROFILES_ACTIVE'].trim()
    if (activeProfile != ctx.deployEnv) {
        error "SPRING_PROFILES_ACTIVE=${activeProfile} 가 배포 환경(${ctx.deployEnv})과 다릅니다. ${specs.common.path} 를 고치세요."
    }

    if (config.portEnvKey) {
        Map<String, String> hostPorts = (config.hostPorts ?: [:]) as Map<String, String>
        String expectedPort = hostPorts[ctx.deployEnv]?.toString()
        if (!expectedPort) {
            error "Jenkinsfile-${config.serviceName} 의 hostPorts 에 ${ctx.deployEnv} 값이 없습니다."
        }
        String actualPort = envValues[config.portEnvKey as String].trim()
        if (actualPort != expectedPort) {
            error "${config.portEnvKey}=${actualPort} 가 ${ctx.deployEnv} 호스트 포트(${expectedPort})와 다릅니다. " +
                'sneezecast 는 컨테이너 내부 포트와 호스트 포트를 같게 둡니다 (backend/docs/deploy-guide.md 포트표).'
        }
    }

    validateDiscoveryEndpoint(config, ctx, envValues)
}

// 클라이언트가 붙을 discovery 주소가 discovery 가 실제로 듣는 주소와 같은지 본다.
// 공통 경로의 SERVICE_DISCOVERY_PORT 가 바뀌면 discovery 를 다시 배포하기 전까지 클라이언트만 엉뚱한 포트로 붙는다.
//   - SERVICE_DISCOVERY_PORT: 모든 잡 (discovery 자신은 hostPorts 검사와 같은 값)
//   - SERVICE_DISCOVERY_HOSTNAME: 클라이언트 잡만. 기대값이 비어 있는 환경(prod, IP 미정)은 필수 키 검사로 갈음한다
void validateDiscoveryEndpoint(Map<String, Object> config, Map<String, String> ctx, Map<String, String> envValues) {
    Map<String, String> expected = discoveryEndpoints()[ctx.deployEnv]
    if (expected == null) {
        error "discovery 기대 주소가 정의되지 않은 배포 환경입니다: ${ctx.deployEnv}"
    }

    String actualPort = envValues['SERVICE_DISCOVERY_PORT']?.trim()
    if (actualPort != expected.port) {
        error "SERVICE_DISCOVERY_PORT=${actualPort ?: '<없음>'} 가 ${ctx.deployEnv} discovery 포트(${expected.port})와 다릅니다. 공통 경로 값을 고치세요."
    }

    if (config.serviceName != 'service-discovery' && expected.host) {
        String actualHost = envValues['SERVICE_DISCOVERY_HOSTNAME']?.trim()
        if (actualHost != expected.host) {
            error "SERVICE_DISCOVERY_HOSTNAME=${actualHost ?: '<없음>'} 가 ${ctx.deployEnv} discovery 주소(${expected.host})와 다릅니다. 공통 경로 값을 고치세요."
        }
    }
}

// 배포에 쓸 env 를 Vault 두 경로에서 읽고 합친 뒤 검사까지 끝낸 결과를 돌려준다.
Map<String, String> readDeployEnvValues(Map<String, Object> config, Map<String, String> ctx) {
    if (!(ctx.deployEnv in ['dev', 'prod'])) {
        error "배포 환경이 아닌 빌드에서 Vault 를 읽으려 했습니다. deployEnv=${ctx.deployEnv}"
    }

    Map<String, Object> specs = resolveVaultSpecs(config, ctx)
    String vaultAddr = resolveVaultAddr()
    String clientToken = loginToVault(vaultAddr)

    Map<String, String> commonValues = readVaultSecretValues(vaultAddr, clientToken, specs.common as Map<String, Object>)
    Map<String, String> serviceValues = specs.service
        ? readVaultSecretValues(vaultAddr, clientToken, specs.service as Map<String, Object>)
        : [:]

    validateServiceOnlyKeys(config, specs, commonValues, serviceValues)
    Map<String, String> merged = mergeVaultSecretValues(specs, commonValues, serviceValues)
    validateDeployEnvValues(config, ctx, specs, merged)

    // 값이 아니라 키 이름만 남긴다. 어느 키가 어느 경로에서 왔는지가 장애 조사에 필요한 정보다.
    echo "Vault 공통 ${specs.common.path}: ${commonValues.keySet().sort().join(', ')}"
    if (specs.service) {
        echo "Vault 서비스 ${specs.service.path}: ${serviceValues.keySet().sort().join(', ')}"
    }

    return merged
}

String renderEnvFile(Map<String, String> secretValues) {
    // 매번 같은 .env.runtime이 생성되도록 key를 정렬해서 렌더링합니다.
    return secretValues
        .keySet()
        .sort()
        .collect { key -> "${key}=${secretValues[key]}" }
        .join('\n') + '\n'
}

void run(Map<String, Object> config) {
    properties([
        // 아티팩트(jar, 서비스당 ~100MB)는 최근 5개 빌드만 보관해 마스터 디스크를 아낀다.
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5')),
        disableConcurrentBuilds(),
        parameters([
            string(
                name: 'TARGET_BRANCH',
                defaultValue: '',
                description: '배포 환경을 판별할 대상 브랜치입니다. 비워두면 CHANGE_TARGET 또는 BRANCH_NAME을 사용합니다.'
            ),
            string(
                name: 'PR_SHA',
                defaultValue: '',
                description: '체크아웃할 커밋 SHA입니다. 비워두면 멀티브랜치 기본 체크아웃을 사용합니다.'
            ),
            string(
                name: 'PR_NUMBER',
                defaultValue: '',
                description: '표시와 로그 확인용 PR 번호입니다. 선택 입력입니다.'
            ),
            booleanParam(
                name: 'RUN_TESTS',
                defaultValue: true,
                description: 'bootJar 전에 대상 모듈 테스트를 실행할지 여부입니다.'
            ),
            booleanParam(
                name: 'SKIP_DEPLOY',
                defaultValue: false,
                description: '배포 가능한 브랜치여도 배포를 건너뛰고 빌드만 수행합니다.'
            ),
            booleanParam(
                name: 'FORCE_DEPLOY',
                defaultValue: false,
                description: '변경 감지와 PR 라벨 게이트를 우회해 이 서비스를 강제 배포합니다. '
                    + '라벨을 빠뜨리고 머지한 커밋을 수동으로 배포할 때 사용합니다. '
                    + '브랜치 규칙(PR 빌드 배포 금지, dev/prod 브랜치 한정)과 SKIP_DEPLOY는 그대로 적용됩니다.'
            ),
            string(
                name: 'DEPLOY_BASE_PARENT',
                defaultValue: 'deploy',
                description: '배포 서버의 사용자 홈 아래에 둘 최상위 디렉터리명입니다.'
            ),
            string(
                name: 'DEPLOY_APP_DIR',
                defaultValue: 'backend',
                description: '프로젝트 배포 디렉터리 아래의 애플리케이션 루트 디렉터리명입니다.'
            ),
            string(
                name: 'VAULT_ADDR',
                defaultValue: 'https://vault.8llow8llowme.com',
                description: 'Vault API 주소입니다.'
            ),
            string(
                name: 'VAULT_AUTH_PATH',
                defaultValue: 'approle',
                description: 'Vault AppRole 인증 mount path입니다.'
            ),
            string(
                name: 'VAULT_ROLE_ID_CREDENTIAL_ID',
                defaultValue: 'sneezecast-vault-role-id',
                description: 'Vault AppRole role_id를 담은 Jenkins Secret text Credential ID입니다.'
            ),
            string(
                name: 'VAULT_SECRET_ID_CREDENTIAL_ID',
                defaultValue: 'sneezecast-vault-secret-id',
                description: 'Vault AppRole secret_id를 담은 Jenkins Secret text Credential ID입니다.'
            ),
            // Vault 경로를 바꾸는 파라미터(혼디가개의 PROJECT_SLUG · VAULT_SECRET_ROOT · VAULT_SECRET_PATH)는 두지 않는다.
            // 경로는 resolveVaultSecretRoot() 상수 + serviceName 으로만 정한다.
            string(
                name: 'VAULT_ENGINE_VERSION',
                defaultValue: '2',
                description: 'HashiCorp Vault KV 엔진 버전입니다.'
            ),
            string(
                name: 'DEPLOY_LOCK_NAME',
                defaultValue: 'sneezecast-backend-deploy',
                description: '백엔드 배포를 직렬화할 때 사용할 Lockable Resource 이름입니다.'
            ),
            string(
                name: 'GITHUB_APP_CREDENTIAL_ID',
                defaultValue: 'github-app-followfollowme-jenkins',
                description: 'PR 라벨 조회에 사용할 GitHub App Credential ID입니다. 비우면 라벨을 조회할 수 없어 배포를 생략하고 빌드를 UNSTABLE로 표시합니다.'
            ),
            string(
                name: 'DEPLOY_LABEL_PREFIX',
                defaultValue: 'backend-',
                description: '배포 대상을 지정하는 PR 라벨 접두어입니다. 라벨명은 {접두어}{serviceName} 형식입니다.'
            )
        ])
    ])

    Map<String, String> ctx = [:]

    stage('배포 문맥 확인') {
        node(config.buildAgentLabel) {
            try {
                deleteDir()
                checkoutSource()
                ctx = resolveGitContext()

                // 모노레포에서 백엔드 잡 5개가 같은 push에 전부 트리거되므로,
                // 이 서비스와 무관한 변경이면 빌드/배포를 건너뛴다.
                List<String> changedFiles = resolveChangedFiles(ctx)
                String affectedScope = resolveAffectedScope(config, changedFiles)

                // 파이프라인 정의 파일'만' 바뀐 PR 은 라벨 스코프로 한 번 더 거른다.
                // 다른 서비스 라벨만 붙은 PR 이 공용 Jenkinsfile 을 수정하면 백엔드 5개 잡 전부가
                // gradle CI 를 돌아 빌더를 점유하고 GitHub 체크를 어지럽히는 것을 막는다.
                // 자기 코드/공용 코드가 바뀐 'own' 은 라벨과 무관하게 반드시 CI 를 돈다.
                if (ctx.isPullRequest == 'true' && affectedScope == 'pipeline') {
                    Map<String, Object> prLabelContext = resolveDeployLabelContext(ctx)
                    if (prLabelContext.resolved == 'true') {
                        List<String> allLabels = prLabelContext.labels as List<String>
                        // 배포 스코프 라벨 규약은 백엔드·프론트 공통이다: backend-{service} / frontend-{service}
                        List<String> scopedLabels = allLabels.findAll { name ->
                            name.startsWith('backend-') || name.startsWith('frontend-')
                        }
                        String expectedLabel = "${params.DEPLOY_LABEL_PREFIX?.trim() ?: 'backend-'}${config.serviceName}"
                        if (scopedLabels && !scopedLabels.contains(expectedLabel)) {
                            echo "PR 라벨(${scopedLabels.join(', ')})이 다른 서비스로 지정되어 파이프라인 정의 변경 CI 를 건너뜁니다."
                            ctx.skipReason = "PR 라벨 스코프 밖(${scopedLabels.join(', ')}) - CI 생략"
                            affectedScope = 'none'
                        }
                    }
                    // 라벨 조회 실패 시에는 안전하게 CI 를 그대로 돈다(fail-open).
                }

                ctx.serviceAffected = (affectedScope != 'none') ? 'true' : 'false'

                // 공용 경로(Jenkinsfile, core 모듈 등)를 건드리면 위 판단으로는 5개 잡이 모두 대상이 되므로,
                // 실제 배포 대상은 PR 라벨로 한 번 더 좁힌다. 라벨이 없으면 배포하지 않는다.
                if (params.FORCE_DEPLOY && ctx.deployEnv in ['dev', 'prod'] && ctx.isPullRequest != 'true') {
                    // 라벨을 빠뜨리고 머지한 커밋을 수동 배포하는 예외 경로.
                    // 같은 커밋 재빌드는 변경 파일이 없어 '변경 없음 - 생략'에도 걸리므로 두 게이트를 함께 우회한다.
                    // 웹훅/자동 빌드는 파라미터 기본값(false)으로 돌기 때문에 사람이 명시적으로 켠 빌드에만 작동한다.
                    echo 'FORCE_DEPLOY가 지정되어 변경 감지와 PR 라벨 게이트를 우회합니다.'
                    currentBuild.description = '강제 배포(FORCE_DEPLOY) - 변경 감지/라벨 게이트 우회'
                    ctx.serviceAffected = 'true'
                    ctx.deployLabelAllowed = 'true'
                } else if (ctx.deployEnv in ['dev', 'prod'] && ctx.serviceAffected == 'true' && ctx.isPullRequest != 'true') {
                    Map<String, Object> labelContext = resolveDeployLabelContext(ctx)
                    ctx.deployLabelAllowed = isServiceDeployAllowedByLabels(config, labelContext) ? 'true' : 'false'
                } else {
                    // PR 빌드와 배포 대상이 아닌 브랜치는 어차피 배포하지 않으므로 라벨을 조회하지 않는다.
                    ctx.deployLabelAllowed = 'true'
                }

                currentBuild.displayName = "#${env.BUILD_NUMBER} ${config.serviceName} ${env.BRANCH_NAME ?: 'n/a'}"

                echo '=== 빌드 문맥 ==='
                echo "현재 브랜치: ${env.BRANCH_NAME ?: '없음'}"
                echo "서비스 그룹: ${config.serviceGroup}"
                echo "서비스 이름: ${config.serviceName}"
                echo "요청 대상 브랜치: ${ctx.requestedTargetBranch ?: '없음'}"
                echo "적용 대상 브랜치: ${ctx.effectiveTargetBranch ?: '없음'}"
                echo "PR SHA: ${ctx.requestedPrSha ?: '없음'}"
                echo "PR 번호: ${ctx.requestedPrNumber ?: '없음'}"
                echo "PR 빌드 여부: ${ctx.isPullRequest}"
                echo "서비스 영향 여부: ${ctx.serviceAffected}"
                echo "라벨 배포 허용 여부: ${ctx.deployLabelAllowed}"
                echo "배포 환경: ${ctx.deployEnv}"
                echo "빌드 에이전트 라벨: ${config.buildAgentLabel}"
                if (ctx.deployEnv in ['dev', 'prod']) {
                    echo "배포 에이전트 라벨: ${resolveDeployAgentLabel(config, ctx)}"
                    echo "호스트 포트: ${(config.hostPorts ?: [:])[ctx.deployEnv] ?: '없음'}"
                    echo "Vault secret: ${describeVaultSpecs(resolveVaultSpecs(config, ctx))}"
                }
                echo "배포 경로 규칙: \$HOME/${params.DEPLOY_BASE_PARENT}/${projectSlug()}/${params.DEPLOY_APP_DIR}/..."
                echo '================='
            } finally {
                deleteDir()
            }
        }
    }

    if (ctx.serviceAffected == 'false') {
        stage('변경 없음 - 생략') {
            echo "이번 변경은 ${config.serviceName}에 영향이 없어 빌드/배포를 건너뜁니다."
            currentBuild.description = ctx.skipReason ?: '변경 없음 - 빌드/배포 생략'
        }
        return
    }

    boolean shouldDeploy = shouldDeployToEnvironment(ctx) && ctx.deployLabelAllowed != 'false'

    // 배포하지 않는 브랜치 빌드는 CI(JAR 빌드)도 돌리지 않습니다.
    // CI 게이트는 PR 빌드가 담당하므로, 머지 빌드에서 라벨 없는 잡까지 gradle 을 돌리면
    // 한 서비스 라벨만 붙은 PR 머지에 나머지 백엔드 잡이 빌더를 점유합니다.
    // 단, SKIP_DEPLOY 는 "배포 없이 빌드만 수행" 이 목적이므로 이 생략 대상에서 제외합니다.
    if (ctx.isPullRequest != 'true' && !params.SKIP_DEPLOY && !shouldDeploy) {
        stage('배포 대상 아님 - 생략') {
            echo "배포하지 않는 브랜치 빌드이므로 JAR 빌드를 생략합니다. deployEnv=${ctx.deployEnv}, deployLabelAllowed=${ctx.deployLabelAllowed}"
            echo 'CI 는 PR 빌드에서 수행됩니다. 이 커밋을 배포하려면 FORCE_DEPLOY 로 수동 실행하세요.'
            if (!currentBuild.description) {
                currentBuild.description = ctx.deployLabelAllowed == 'false'
                    ? '배포 대상 라벨 미지정 - 빌드/배포 생략'
                    : '배포 대상 브랜치 아님 - 빌드/배포 생략'
            }
        }
        return
    }

    if (shouldDeploy) {
        // gradle 을 돌기 전에 Vault 설정부터 검사한다. 필수 키 누락 · 중복 키 · 프로필/포트 불일치는
        // 빌드 몇 분을 쓰고 나서 배포 단계에서 알 이유가 없다. 값은 검사만 하고 빌드 env 로 넘기지 않는다.
        // (배포 단계에서 한 번 더 읽고 검사한다 — 그사이 Vault 값이 바뀌었을 수 있다)
        stage('배포 설정 검증') {
            node(config.buildAgentLabel) {
                readDeployEnvValues(config, ctx)
            }
        }
    }

    stage('JAR 빌드') {
        node(config.buildAgentLabel) {
            try {
                deleteDir()
                checkoutSource()

                // gradlew 실행 권한은 git 모드(100755)에 맡긴다 — backend-ci 가 chmod 없이 돌아 모드 결함을 잡는다.
                dir('backend') {
                    String gradleCommand = params.RUN_TESTS
                        ? "./gradlew :${config.modulePath}:test :${config.modulePath}:bootJar --no-daemon --parallel --build-cache --stacktrace"
                        : "./gradlew :${config.modulePath}:bootJar --no-daemon --parallel --build-cache --stacktrace"

                    sh """#!/usr/bin/env bash
set -euo pipefail
${gradleCommand}
"""
                }

                if (shouldDeploy) {
                    // 아티팩트 보관과 stash는 배포 스테이지 전달용이므로 배포하는 빌드에서만 수행한다.
                    // (PR 빌드는 CI만 하므로 ~100MB jar를 마스터에 남기지 않는다.)
                    archiveArtifacts artifacts: "${config.fsPath}/build/libs/*.jar", fingerprint: true
                    stash(
                        name: "bundle-${config.serviceName}",
                        includes: "${config.fsPath}/build/libs/*.jar,${config.fsPath}/${config.dockerfile},${config.fsPath}/${config.composeFile}"
                    )
                } else {
                    echo '배포하지 않는 빌드(CI 전용)이므로 아티팩트 보관과 stash를 생략합니다.'
                }
            } finally {
                deleteDir()
            }
        }
    }

    if (shouldDeploy) {
        stage("${ctx.deployEnv} 환경 배포") {
            // lock 을 잡은 채 agent 를 기다리므로 상한을 둔다. prod agent(deploy-backend-prod)는 아직 없어서,
            // main 머지 빌드가 상한 없이 agent 를 기다리면 lock 을 쥔 채 dev 배포까지 전부 막는다.
            // 상한에 걸리면 빌드는 FAILURE 가 아니라 ABORTED 로 끝난다.
            timeout(time: 30, unit: 'MINUTES') {
                lock(resource: params.DEPLOY_LOCK_NAME?.trim() ?: 'sneezecast-backend-deploy') {
                    node(resolveDeployAgentLabel(config, ctx)) {
                        try {
                            deleteDir()
                            unstash "bundle-${config.serviceName}"

                            // 공통 + 자기 서비스 경로만 읽는다. 다른 서비스의 키는 이 파일에 들어오지 않는다.
                            Map<String, String> deployEnvValues = readDeployEnvValues(config, ctx)
                            writeFile file: '.env.runtime', text: renderEnvFile(deployEnvValues)

                            sh """#!/usr/bin/env bash
set -euo pipefail

SERVICE_DIR="\${HOME}/${params.DEPLOY_BASE_PARENT}/${projectSlug()}/${params.DEPLOY_APP_DIR}/${config.deploySubdir}"
mkdir -p "\${SERVICE_DIR}"
rsync -a --delete "${config.fsPath}/" "\${SERVICE_DIR}/"
install -m 600 .env.runtime "\${SERVICE_DIR}/.env.runtime"

jar_file="\$(find "\${SERVICE_DIR}/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | sort | head -n 1)"
if [ -z "\${jar_file}" ]; then
  jar_file="\$(find "\${SERVICE_DIR}/build/libs" -maxdepth 1 -type f -name '*.jar' | sort | head -n 1)"
fi
if [ -z "\${jar_file}" ]; then
  echo "배포할 JAR 파일을 찾지 못했습니다: \${SERVICE_DIR}/build/libs"
  find "\${SERVICE_DIR}" -maxdepth 4 -type f | sort
  exit 1
fi
install -m 644 "\${jar_file}" "\${SERVICE_DIR}/app.jar"

docker network inspect 8llow8llowme-net >/dev/null 2>&1 || docker network create 8llow8llowme-net >/dev/null

cd "\${SERVICE_DIR}"
test -s .env.runtime
test -s app.jar

# compose 프로젝트명을 디렉터리 basename(api-gateway, auth-service ...)에 맡기지 않는다.
# 같은 호스트의 다른 프로젝트가 같은 basename 을 쓰면 하나의 프로젝트로 묶여,
# `up --remove-orphans` 가 상대 프로젝트의 같은 서비스 컨테이너를 orphan 으로 지워 버린다.
# (BossPickSeoul · 혼디가개가 실제로 서로의 auth 컨테이너를 내린 적이 있다.)
COMPOSE_PROJECT="${config.containerNamePrefix}"
CONTAINER_NAME="${config.containerNamePrefix}-${ctx.deployEnv}"

# 같은 이름의 컨테이너가 다른 compose 프로젝트에 묶여 있으면 먼저 지운다.
# 그대로 두면 compose 가 "container name already in use" 로 실패한다.
existing_project="\$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "\${CONTAINER_NAME}" 2>/dev/null || true)"
if [ -n "\${existing_project}" ] && [ "\${existing_project}" != "\${COMPOSE_PROJECT}" ]; then
  echo "기존 컨테이너 \${CONTAINER_NAME} 가 다른 compose 프로젝트(\${existing_project})에 속해 있어 정리합니다."
  docker rm -f "\${CONTAINER_NAME}" >/dev/null
fi

docker compose -p "\${COMPOSE_PROJECT}" --env-file .env.runtime -f ${config.composeFile} config >/dev/null
docker compose -p "\${COMPOSE_PROJECT}" --env-file .env.runtime -f ${config.composeFile} up -d --build --remove-orphans ${config.composeServiceName}-${ctx.deployEnv}

for attempt in \$(seq 1 30); do
  state="\$(docker inspect -f '{{.State.Status}}' "\${CONTAINER_NAME}" 2>/dev/null || true)"

  if [ "\${state}" = "running" ]; then
    # 첫 running 만 보고 끝내면 Spring 컨텍스트가 기동 중에 죽어도 초록이 되고, 그 뒤로는
    # restart: unless-stopped 가 재시작 루프로 숨긴다. 기동이 끝날 만큼 기다린 뒤
    # 아직 running 이고 재시작이 0회인지까지 본다.
    sleep 45
    settled="\$(docker inspect -f '{{.State.Status}} {{.RestartCount}}' "\${CONTAINER_NAME}" 2>/dev/null || true)"
    if [ "\${settled}" != "running 0" ]; then
      echo "컨테이너가 기동 직후 안정되지 않았습니다 (status restartCount = \${settled:-<none>})."
      docker logs --tail 200 "\${CONTAINER_NAME}" || true
      exit 1
    fi
    docker compose -p "\${COMPOSE_PROJECT}" --env-file .env.runtime -f ${config.composeFile} ps ${config.composeServiceName}-${ctx.deployEnv}
    exit 0
  fi

  if [ "\${state}" = "exited" ] || [ "\${state}" = "dead" ]; then
    docker logs --tail 200 "\${CONTAINER_NAME}" || true
    exit 1
  fi

  sleep 5
done

docker logs --tail 200 "\${CONTAINER_NAME}" || true
exit 1
"""
                        } finally {
                            deleteDir()
                        }
                    }
                }
            }
        }
    } else {
        stage('배포 생략') {
            echo "배포를 생략합니다. deployEnv=${ctx.deployEnv}, skipDeploy=${params.SKIP_DEPLOY}, isPullRequest=${ctx.isPullRequest}, deployLabelAllowed=${ctx.deployLabelAllowed}"
            // 라벨 확인 실패 시에는 isServiceDeployAllowedByLabels가 사유를 담은 description을 이미 설정한다.
            if (ctx.deployLabelAllowed == 'false' && !currentBuild.description) {
                currentBuild.description = '배포 대상 라벨 미지정 - 배포 생략'
            }
        }
    }
}

return this
