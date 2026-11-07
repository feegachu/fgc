# FGC 오픈소스 라이선스 점검표 v1.0

| 항목 | 내용 |
|---|---|
| 요구사항 | FGC-COR-008 오픈소스 라이선스 준수. 인수조건: 배포 산출물 주요 의존성 라이선스가 목록화되고 금지 라이선스가 없다 |
| 기준 코드 | develop `ded47b31` |
| 점검일 | 2026-11-07 |
| SBOM | `docs/FGC_SBOM_v1_0.spdx.json` (GitHub 의존성 그래프, SPDX 2.3, 패키지 720개, 개발 도구 포함) |
| 근거 | 요구사항 근거 SRC-006은 근거대장에서 누락 상태다. 아래 판정 기준은 프로젝트 가정으로 정했다 |

## 1. 판정 기준 (프로젝트 가정)

배포 형태는 팀 서버에서 컨테이너로 운영하는 웹 서비스이고, 소스는 공개 저장소에 있다. 업무 규칙이 아니라 프로젝트 가정이며, 배포 형태가 바뀌거나 기관 기준이 나오면 다시 정한다.

| 판정 | 라이선스 | 조건 |
|---|---|---|
| 허용 | MIT, ISC, BSD-2/3-Clause, Apache-2.0, EDL-1.0, PostgreSQL, OFL-1.1(폰트), CC0 | 고지문을 유지한다 |
| 조건부 허용 | LGPL-2.1, EPL-1.0/2.0, MPL-2.0, GPL-2.0 WITH Classpath-exception | 수정하지 않고 링크해 쓴다. 수정하면 그 부분을 공개한다 |
| 금지 | AGPL, 예외 조항 없는 GPL, SSPL, BUSL, Commons Clause, CC BY-NC | 서비스 제공만으로 전체 소스 공개 의무가 생기거나 상업 이용이 막힌다 |
| 확인 필요 | 라이선스 미표기, 비표준 표기 | 원 프로젝트의 공식 안내로 확인한 뒤 판정한다 |

## 2. 결과 요약

| 구분 | 대상 | 허용 | 조건부 허용 | 금지 | 확인 필요 |
|---|---|---|---|---|---|
| 백엔드 실행 의존성 (`runtimeClasspath`) | 136 | 130 | 6 | 0 | 0 |
| 프런트 운영 의존성 (`npm ls --omit=dev`) | 14 | 14 | 0 | 0 | 0 |
| 실행 이미지 | 3 | 2 | 1 | 0 | 0 |
| 폰트, 아이콘 | 4 | 4 | 0 | 0 | 0 |
| 그림, 애니메이션 | 10 | 0 | 0 | 0 | 10 |

**금지 라이선스는 없다.** 그림과 애니메이션은 팀원이 추가한 파일이라 제작 도구와 원본을 담당자에게 확인해야 한다(7장).

## 3. 조건부 허용 목록

| 패키지 | 버전 | 라이선스 | 사용 방식 |
|---|---|---|---|
| `ch.qos.logback:logback-classic` | 1.5.34 | EPL-1.0 OR LGPL-2.1 (이중 라이선스) | 수정하지 않고 jar로 링크 |
| `ch.qos.logback:logback-core` | 1.5.34 | EPL-1.0 OR LGPL-2.1 (이중 라이선스) | 수정하지 않고 jar로 링크 |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | 수정하지 않고 jar로 링크 |
| `jakarta.transaction:jakarta.transaction-api` | 2.0.1 | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | 수정하지 않고 jar로 링크 |
| `org.aspectj:aspectjweaver` | 1.9.25.1 | EPL-2.0 (포함 구성요소의 Apache, BSD 고지 포함) | 수정하지 않고 jar로 링크 |
| `org.hibernate.orm:hibernate-core` | 6.6.53.Final | LGPL-2.1 | 수정하지 않고 jar로 링크 |

## 4. 스캐너 표기를 공식 라이선스로 고친 것

SBOM의 라이선스는 소스 파일 고지까지 합쳐 적어 실제 배포 조건과 다를 때가 있다. 아래는 각 프로젝트의 공식 라이선스로 판정했다.

| 패키지 | SBOM 표기 | 공식 라이선스 |
|---|---|---|
| `jakarta.xml.bind` (1개: jakarta.xml.bind-api) | BSD-3-Clause AND LicenseRef-scancode-generic-export-compliance | BSD-3-Clause (EDL-1.0) |
| `org.eclipse.angus` (1개: angus-activation) | BSD-3-Clause AND LicenseRef-scancode-generic-export-compliance | BSD-3-Clause (EDL-1.0) |
| `org.flywaydb` (1개: flyway-database-postgresql) | NOASSERTION | Apache-2.0 |
| `org.springframework` (12개: spring-aop, spring-aspects, spring-beans, spring-context, spring-core, spring-expression, spring-jcl, spring-jdbc, spring-orm, spring-tx, spring-web, spring-webmvc) | Apache-2.0 AND BSD-3-Clause AND EPL-1.0 AND LicenseRef-scancode-other-copyleft | Apache-2.0 |
| `ch.qos.logback` (2개) | EPL-2.0 AND LGPL-2.1 | EPL-1.0 또는 LGPL-2.1 이중 라이선스 |
| `jakarta.annotation`, `jakarta.transaction` API (2개) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | EPL-2.0을 택해 조건부 허용 |
| `jakarta.inject` API | Apache-2.0 OR (Apache-2.0 AND EPL-2.0) | Apache-2.0 (허용) |
| `jakarta.persistence` API | BSD-3-Clause OR EPL-2.0 | EDL-1.0(BSD-3-Clause)을 택해 허용 |

## 5. 백엔드 주요 직접 의존성

| 패키지 | 버전 | 라이선스 |
|---|---|---|
| `org.springframework.boot:spring-boot` | 3.5.16 | Apache-2.0 |
| `org.springframework:spring-webmvc` | 6.2.19 | Apache-2.0 |
| `org.postgresql:postgresql` | 42.7.13 | BSD-2-Clause |
| `org.flywaydb:flyway-database-postgresql` | 11.7.2 | Apache-2.0 |
| `io.micrometer:micrometer-registry-prometheus` | 1.15.12 | Apache-2.0 |
| `org.springframework.security:spring-security-core` | 6.5.11 | Apache-2.0 |
| `org.springframework.security:spring-security-oauth2-resource-server` | 6.5.11 | Apache-2.0 |
| `org.springframework.batch:spring-batch-core` | 5.2.6 | Apache-2.0 |
| `org.hibernate.orm:hibernate-core` | 6.6.53.Final | LGPL-2.1 |
| `com.zaxxer:HikariCP` | 6.3.3 | Apache-2.0 |
| `org.flywaydb:flyway-core` | 11.7.2 | Apache-2.0 |
| `io.github.openfeign.querydsl:querydsl-jpa` | 7.7 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.7 | Apache-2.0 |
| `com.nimbusds:nimbus-jose-jwt` | 9.37.4 | Apache-2.0 |
| `com.openai:openai-java` | 4.94.0 | Apache-2.0 |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.10.0 | Apache-2.0 |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | Apache-2.0 |
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.60 | Apache-2.0 |

컴파일에만 쓰는 Lombok(MIT)과 시험, 빌드 도구(Testcontainers, JUnit, Vite, ESLint, Playwright 등)는 배포물에 들어가지 않아 판정에서 뺐다. SBOM에는 들어 있다.

## 6. 프런트 운영 의존성

| 패키지 | 버전 | 라이선스 |
|---|---|---|
| `@remix-run/route-pattern` | 0.22.1 | MIT |
| `@rive-app/canvas` | 2.48.0 | MIT |
| `@rive-app/react-canvas` | 4.38.0 | MIT |
| `@tanstack/query-core` | 5.105.0 | MIT |
| `@tanstack/react-query` | 5.105.0 | MIT |
| `@types/react` | 19.3.0 | MIT |
| `@types/react-dom` | 19.3.0 | MIT |
| `cookie-es` | 3.1.1 | MIT |
| `csstype` | 3.2.3 | MIT |
| `react` | 19.3.1 | MIT |
| `react-dom` | 19.3.1 | MIT |
| `react-router` | 8.5.1 | MIT |
| `scheduler` | 0.28.0 | MIT |
| `zustand` | 5.0.16 | MIT |

## 7. 실행 이미지, 폰트, 아이콘, 그림, 데이터

| 자산 | 파일 | 라이선스 | 고지 | 판정 |
|---|---|---|---|---|
| Tomcat 10.1 + Eclipse Temurin JRE 21 (API 실행 이미지) | `Containerfile.api` | Apache-2.0, GPL-2.0 WITH Classpath-exception | 이미지에 포함 | 조건부 허용 |
| nginx (웹 실행 이미지) | `Containerfile.web` | BSD-2-Clause | 이미지에 포함 | 허용 |
| PostgreSQL 17 (DB 이미지) | `Containerfile.db`, compose | PostgreSQL License | 이미지에 포함 | 허용 |
| Pretendard (FGC Sans) | `frontend/public/fonts/fgc-sans-variable.woff2` | OFL-1.1 | `fgc-sans-OFL.txt` | 허용 |
| Noto Sans KR | `frontend/public/fonts/noto-sans-kr-variable.woff2` | OFL-1.1 | `noto-sans-kr-OFL.txt` (이번에 추가) | 허용 |
| Material Symbols Rounded | `frontend/public/fonts/material-symbols-rounded.woff2` | Apache-2.0 | `material-symbols-LICENSE.txt` (이번에 추가) | 허용 |
| 나눔고딕 (환수 통지서 PDF) | `src/main/resources/fonts/NanumGothic-Regular.ttf` | OFL-1.1 | `OFL.txt` | 허용 |
| 로고, 파비콘 (3개) | `frontend/public/images/brand/logo-*.png`, `favicon-32.png` | 팀 제작으로 추가(0a594ecc) | 없음 | 확인 필요 |
| 설계사 마스코트, 상품 추천 AI 그림 (6개) | `frontend/public/images/brand/agent-mascot.png`, `chatbot-*.png` | 팀 제작으로 추가(8e547fb6, a333b910) | 없음 | 확인 필요 |
| 피코 움직임 | `frontend/src/features/agent-home/mascot/pico-motion-purple.riv` | 팀 제작으로 추가(eef58863) | 없음 | 확인 필요 |
| 시드, 시연 데이터 | `src/main/resources/db/demo*`, `scripts/demo-data` | 프로젝트 합성 데이터 | 파일 머리 주석 | 허용 |

운영체제 패키지는 실행 이미지에 별도 프로그램으로 함께 담기며 수정하지 않는다. 이미지를 외부에 배포하면 각 배포판의 소스 제공 안내를 따른다.

## 8. 남은 확인과 갱신 방법

1. 그림과 애니메이션 10개: 추가한 팀원에게 제작 도구(직접 그림, 생성형 도구, 외부 소스)와 원본을 확인해 7장에 적는다. 외부 소스면 그 라이선스를 적고 고지 파일을 둔다.
2. 의존성을 추가하면 아래로 다시 뽑아 2~6장을 고친다.
   - 백엔드: `./gradlew -q dependencies --configuration runtimeClasspath`
   - 프런트: `npm ls --omit=dev --all` (frontend 폴더)
   - SBOM: `gh api repos/feegachu/fgc/dependency-graph/sbom --jq .sbom > docs/FGC_SBOM_v1_0.spdx.json`
