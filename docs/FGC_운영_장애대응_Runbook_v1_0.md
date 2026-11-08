# FGC 운영, 장애대응 Runbook v1.0

| 항목 | 내용 |
|---|---|
| 요구사항 | FGC-PSR-005 운영, 장애대응 Runbook (요구사항명세서 v3.0 PSR 시트. 원 근거 SRC-005, SRC-006. SRC-006은 근거대장에 "누락"으로 원문이 없다) |
| 인수조건 | 장애 모의훈련에서 Runbook을 따라 탐지, 조치, 복구, 기록을 완료한다 |
| 기준 코드 | develop `5f21741a` (2026-11-04, #865 병합, Flyway V80). 열린 PR을 인용할 때는 번호와 커밋을 적었다. "지금"은 이 커밋을 뜻한다 |
| 모의훈련 | 부록 A. 격리 환경에서 돌린 것과 돌리지 못한 것을 나눠 적었다. 훈련 이미지는 `185dc854`이고 그 뒤 병합분은 부록 A에 구분했다 |
| 짝 문서 | `docs/FGC_사용자_관리자_매뉴얼_v1_0.md` |

**읽는 법.** 장애가 났으면 바로 아래 "장애 대응 한 장"부터 본다. 각 장은 PSR-005 순서(탐지, 초기 판단, 담당, 조치, 검증, 사후 기록)를 따른다.

**공개 저장소다.** 주소, 계정 비밀번호, SSH 별칭, 키 값은 `<자리표시자>`로 적었다. 실제 값은 저장소 밖에서 받는다. 명령을 실행하며 비밀값을 화면, 로그, 이슈에 남기지 않는다.

**근거 표기.** `[SRC-xx]`는 근거대장 ID다. `[T번호]`는 부록 A의 훈련 기록이고 근거대장 ID는 SRC-077이다. 코드만 읽어 확인한 내용은 `코드 대조`라고 적었고 [T번호]를 붙이지 않았다. 어느 자료에도 없는 운영 규칙은 "가상 GA 정책으로 정함"이라고 적고 다시 열 조건을 함께 적었다. 이 문서는 수수료, 한도, 규제 판정 규칙을 새로 정하지 않는다.

| 장 | 근거 |
|---|---|
| 0 | 팀 10차 회의, 트래커 #755 4번 [SRC-067]. 발표 서버 [SRC-076]. 담당 배정은 제안 |
| 1-6 | PR #870 [SRC-028], 훈련 T30. 앞단 동작은 미측정 |
| 2 | `Jenkinsfile.*`, `infra/scripts/*`, PR #836 [SRC-076], PR #870 6절 [SRC-028], 훈련 T1~T3, T7, T22~T24 |
| 3 | 운영정책서 '일일 변경분 처리', 인터페이스정의서 §7-5, [SRC-065], 훈련 T8~T12 |
| 4 | 시드 명세서 §5, [SRC-065], [SRC-076], [SRC-027 D-04], 훈련 T4~T7, T27, T28 |
| 5 | [SRC-060], [SRC-075], 인터페이스정의서 IF-EXT-03, 훈련 T16~T19, T26. 예산 임계값은 가상 GA 정책 |
| 6 | 인터페이스정의서 §4-4-4, §7-6, FUN-045 (#672, PR #844), V80, 코드 대조 [SRC-028]. 실패 사유 문구는 화면정의서 공통규칙 10, [SRC-074]. 훈련 T13, T14는 #844 전 이미지, T31은 #844 뒤 이미지. 6-3절은 PM 결정, 팀 공유 전 |
| 7 | V74, V79, #719 코멘트, 인터페이스정의서 §4-4-2, PR #831, #835, 코드 대조 [SRC-028]. 훈련 T15는 처리기가 없던 이미지. PM 결정, 팀 공유 전 |
| 8 | PR #812, 트래커 4번 [SRC-067], 훈련 T21 |
| 9 | 트래커 4번, PMR-007, TER-010 [SRC-067], 훈련 T20 |

---

## 0. 장애 대응 한 장

10차 회의가 정한 세 시나리오(CI/CD 서버 다운, 이미지 저장소 장애, DB 손상)의 담당과 조치다(트래커 #755 4번) [SRC-067]. 11/18 발표 서버는 신한 서버다 [SRC-076].

| | CI/CD 서버 다운 | 이미지 저장소(GHCR) 장애 | DB 손상 |
|---|---|---|---|
| **탐지** | Jenkins 웹 접속 실패, 배포 job이 시작되지 않음. GitHub Actions 장애면 develop 병합 뒤 `sha-<커밋>` 이미지가 생기지 않음 | `docker pull`, `podman pull`이 시간 초과, `manifest unknown` | `/actuator/health`가 `DOWN`, API 로그의 SQL 오류, `pg_isready` 실패, 데이터가 이상하다는 신고 |
| **먼저 볼 것** | 서비스 영향 없음이 보통이다. 앱 health 확인(1-3절) | 떠 있는 앱은 영향 없음. 재기동은 받아 둔 이미지로 된다 | 쓰기를 더 받기 전에 멈춘다. 데이터 잘못인지 서버 장애인지 구분 |
| **주담당 / 보조** | DevOps 강호원 / PM 이예소 | DevOps 강호원 / PM 이예소 | PM 이예소(복원 결정) / DevOps 강호원(실행), 백엔드 강현준(데이터 확인) |
| **조치** | 1. 배포를 멈추고 알린다. 2. Jenkins 복구. 3. 급하면 서버에서 수동 배포(2-3절). PL 승인을 받아 RFC에 남긴다 | 1. 배포를 멈춘다. 2. 서버의 이미지 확인(`docker image inspect <digest>`). 3. 받아 둔 digest로만 재기동. 4. 복구 뒤 pull 재시험 | 1. `web`, `api` 중지. 2. 현재 DB 추가 백업. 3. 백업 선택과 손실 구간 승인. 4. 복원(2-6절, 9-2절). 5. API, 웹 기동 |
| **하지 않을 일** | 서버에서 다시 빌드 | 태그로 받기, `latest` 사용 | 복원 전 추가 백업 생략. 운영 DB에 직접 수정 |
| **복구 확인** | Jenkins에서 `dev-fgc` 1회 성공 | digest pull 성공 | health `UP`, Flyway 버전, 건수, 로그인, 파일 다운로드 |
| **훈련** | 미확인, 리허설(11/10) | 없는 digest pull 실패만 확인 [T24]. 실제 장애는 미확인 | 복원 절차 실행 [T3, T7, T20] |

담당은 스프린트 계획(10/27)의 역할과 `Jenkinsfile.*`의 승인자를 기준으로 **이 문서가 제안한 것이다.** 10차 회의의 "서버별 주담당 1명 + 백업 담당 1명"은 아직 정해지지 않았다. 11/10 리허설 전에 팀이 확인한다(근거 없음, 확인 필요).

**연락망**

| 역할 | 이름 | 맡는 일 |
|---|---|---|
| PM, 팀장 | 이예소 | 운영 배포 실행, 복원 결정, 사용자 공지 |
| PL, 백엔드 | 강현준 | 운영 배포 승인(Jenkins input), 데이터 확인 |
| DevOps, QA | 강호원 | 장애 분석, Jenkins, 배치 재시작 |
| 정산 도메인 | 손가영 | 환수, 설계사 화면 확인 |
| 프론트엔드 | 박민준 | 화면 장애 확인 |
| GA관리자 역할 승인 | 근거 없음, 확인 필요 | 3장, 6-3절, 7장 복구 승인. 리허설 전 지정 |

전화번호와 메신저 주소는 저장소에 적지 않는다. 실제 연락 수단은 리허설 전에 팀 공간에 모은다(근거 없음, 확인 필요).

**세 시나리오 밖의 증상**

| 증상 | 원인 | 절 |
|---|---|---|
| 로그인 화면에 `'로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.'` (HTTP 429) | 로그인 시도 제한. 서버 장애가 아니다 | 1-6절 |
| 월 통합검증 실패, 배치가 멈춤 | 6-1절 표로 가른다 | 6장 |
| 계약 상세에 `'자동 재시도를 모두 쓴 후속 처리가 있습니다.'`, 그 계약의 지급 확정이나 스케줄 확정이 막힘 | 상태 사건 후속 작업 소진 | 7장 |

---

## 1. 공통

### 1-1. 환경

| 환경 | 배포 방식 | 앱 | DB, 파일 | 정의 |
|---|---|---|---|---|
| AWS 개발계, 검증계 | Jenkins `dev-fgc`, `stage-fgc` → `infra/scripts/deploy-host.sh` | Podman Pod `fgc-app` (`podman/fgc-app-combined.yaml`) | DB Pod는 별도. 파일은 named volume `fgc-files` | `Jenkinsfile.fgc` |
| AWS 운영 | Jenkins `release-fgc` (PL 승인) | WAS, WEB 2대 (`podman/fgc-app-was.yaml`, `fgc-app-web.yaml`) | 파일은 named volume `fgc-files` | `Jenkinsfile.fgc` |
| 신한 서버 (11/18 발표) | Jenkins `release-shinhan-fgc` (PL 승인) → `infra/scripts/deploy-shinhan.sh` | Docker Compose 프로젝트 `fgc-shinhan` (`infra/shinhan/compose.yaml`) | volume `fgc-shinhan_pgdata`, `fgc-shinhan_files` | `Jenkinsfile.shinhan` |

두 경로 모두 다시 빌드하지 않고 CI가 올린 이미지의 digest를 승격한다. 프로필은 신한이 `prod`, Pod는 `ENV_NAME`이다. 설정 파일은 `src/main/resources/application.yml` 하나이고 값은 환경변수로 받는다.

### 1-2. 신한 서버 명령 준비

이 문서의 신한 명령은 아래 변수를 쓴다. 상태 폴더는 `deploy-shinhan.sh`가 쓰는 경로다.

```bash
state=~/fgc-stg
compose=(docker compose --project-directory "$state" --env-file "$state/.env" --env-file "$state/release.env" -f "$state/compose.yaml")
psql_fgc() { "${compose[@]}" exec -T db psql -U fgc -d fgc -X -q "$@"; }
```

SQL은 표준 입력으로 넘긴다. 값이 프로세스 목록에 보이지 않게 하기 위해서다. 아래의 `\set` 줄과 함께 그대로 붙여 넣는다.

### 1-3. 공통 초기 판단

| 확인 | 신한 | AWS | 읽는 법 |
|---|---|---|---|
| 컨테이너 | `"${compose[@]}" ps` | `podman pod ps`, `podman ps` | **`Up`이어도 앱이 살아 있다는 뜻이 아니다.** 외장 Tomcat은 WAR 기동이 실패해도 프로세스가 남는다 [T7] |
| 앱 | `curl -fsS http://127.0.0.1:18081/actuator/health` | 같은 경로를 `API_BIND` 포트로 | `UP`이어야 한다. **404면 WAR 기동 실패다** (Flyway 검증 실패 포함) [T7]. `DOWN`이면 DB부터 본다 |
| 웹 | `curl -I http://127.0.0.1/login` | `WEB_BIND` 포트 | 200 |
| 로그 | `"${compose[@]}" logs --tail=200 api` | `podman logs` | 사용자가 준 요청번호로 찾는다. 오래된 줄은 `--tail` 없이 받아 요청번호로 거른다. 신한 서버는 컨테이너당 10MB 파일 3개만 남으니(`infra/shinhan/compose.yaml`) 장애 직후 찾는다 |
| 디스크 | `df -h`, `docker system df` | `df -h`, `podman system df` | 8장. 파일 볼륨 가득 참은 health에 드러나지 않는다 [T21] |
| DB | `"${compose[@]}" exec -T db pg_isready -U fgc -d fgc` | DB 컨테이너에서 같은 명령 | |
| 배치 | 아래 SQL | 같은 SQL | `RUNNING`이 오래 남으면 6-3절, `계약수동`이 `CREATED`로 오래 남으면 6-5절 |
| 로그인 | `"${compose[@]}" logs --tail=200 web` | web 컨테이너의 `podman logs` | `limiting requests`가 보이면 1-6절 |

```sql
SELECT validation_run_id, run_type, status, current_step, started_at, left(failure_message, 120)
  FROM fgc.validation_run ORDER BY validation_run_id DESC LIMIT 5;
```

자동 알림(ECR-007, PER-006)은 #674 범위다. 저장소 기준으로 신한 서버용 알림 구성은 확인되지 않았다(근거 없음, 확인 필요). 그때까지 발표 당일 점검(트래커 7번)과 사용자 신고로 탐지한다.

### 1-4. 사후 기록 양식

장애와 훈련마다 트래커 #755 또는 해당 이슈 댓글에 남긴다. 비밀값은 적지 않는다.

```text
사건 ID / 담당 / 승인자:
환경, 커밋, api/web digest, Flyway 버전:
증상 / 탐지 수단 / 탐지 시각:
초기 판단 (가설, 확인한 로그, 배제한 원인):
사용자 영향 / 데이터 영향 (손실 구간):
조치 명령 요약 / 복구 시각:
검증 결과 (SQL, 화면, 요청번호):
감사 기록 번호 (audit_log_id), 예외 번호, 실행 번호:
재발 방지 / 담당 / 기한:
```

### 1-5. 승인 규칙

운영 DB를 손으로 바꾸는 절차는 이 문서의 SQL로만 한다. 각 SQL은 승인자, 실행자, 사유, 티켓을 `audit_log`에 같은 트랜잭션으로 남긴다. `audit_log`는 수정, 삭제가 막혀 있다(`trg_audit_log_append_only`).

- 승인자: `GA_ADMIN` 역할 계정. 실행자: 승인자와 다른 등록 계정(`SYSTEM_ADMIN` 운영자). SQL이 역할과 분리를 검사한다.
- `ticket`: RFC나 이슈 댓글 번호. 승인 원문은 거기에 남긴다.
- 근거: 일 배치 복구는 SRC-065. 나머지 수동 절차(6-3절, 7장)는 같은 통제를 따르며 **PM 결정, 팀 공유 전**이다.

### 1-6. 로그인 시도 제한 (429)

서버 장애가 아니다. nginx가 로그인 요청(`/api/v1/auth/login`)만 세어 넘치면 API로 보내지 않고 429를 돌려준다. 성공과 실패를 가리지 않고 세며, 비밀번호나 계정 상태를 보지 않고 계정을 잠그지도 않는다(PR #870). 값은 업무 규칙이 아니라 AWS 경로와 맞춘 운영 설정이다 [SRC-028]. 다시 열 조건: 시연이나 부스에서 정상 로그인이 막힐 때, 앞단 헤더 동작을 측정했을 때(2-4절).

| 단계 | 내용 |
|---|---|
| 탐지 | 로그인 화면의 `'로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.'`. web 로그의 `limiting requests` |
| 초기 판단 | 다른 회선에서 로그인되면 서버는 정상이다. 다른 회선도 같은 안내면 전원이 한 묶음으로 세어지는 것일 수 있으니 2-4절 표로 가른다. `'아이디 또는 비밀번호가 맞지 않습니다.'`는 이 제한이 아니다 |
| 담당 | DevOps 확인. 값을 바꾸면 배포와 같이 PL 승인 |
| 조치 | 기다렸다가 한 명씩 다시 시도한다. 정상 사용이 반복해서 막히면 값을 올린다 |
| 검증 | 같은 위치에서 로그인 성공, web 로그에 새 `limiting requests` 없음 |
| 사후 기록 | 1-4절. 값을 바꿨으면 전후 값과 이유 |

**값과 대기 시간 [T30]**

| 항목 | 값 |
|---|---|
| 제한 | 분당 30회(2초에 1회)에 순간 10회를 더 허용. `rate=30r/m`, `burst=10` |
| 연속 시도 | 11번까지 통과, 12번째부터 429 |
| 막힌 뒤 | 2초마다 1번씩 풀린다. 22초 쉬면 다시 11번 된다 |
| 세는 기준 (신한) | `X-Forwarded-For`의 마지막 값. 앞쪽 값은 위조할 수 있어 보지 않는다. 헤더가 없으면 세지 않는다. 마지막 값이 모두 같으면 전원이 한 묶음이다. 앞단 동작은 미측정이다(2-4절) |
| 세는 기준 (AWS) | 접속 주소(`$binary_remote_addr`). 코드 대조 |

**같은 공유기에서 여러 명이 로그인할 때 (시연장, 부스).** 밖에서 보면 주소가 하나이므로 앞단이 같은 값을 붙이면 **한 사용자로 세어진다.** 20명이 거의 동시에 누르면 11명이 통과하고 9명이 안내를 본다 [T30]. 막힌 사람은 몇 초 뒤 다시 누르면 되고, 한 명씩 로그인하면 분당 30명까지 막히지 않는다. 다른 사용자로 세어지는 사람에게는 영향이 없다 [T30].

**값을 올리는 곳 (코드 대조)**

| 경로 | 파일 | 재배포 |
|---|---|---|
| 신한 | `infra/shinhan/nginx-http.conf`의 `rate=30r/m`, `infra/shinhan/nginx-guard.conf`의 `burst=10` | PR 병합 뒤 `release-shinhan-fgc`를 같은 `SOURCE_BUILD`로 다시 돌린다(2-2절). 이미지는 그 빌드의 digest 그대로이고, 설정은 작업이 체크아웃한 저장소의 `infra/shinhan/`을 `deploy-shinhan.sh`가 복사한다(`Jenkinsfile.shinhan`의 `checkout scm`). 병합 위치는 이 작업이 체크아웃하는 브랜치다(Jenkins 작업 설정, 저장소 밖) |
| AWS | `infra/scripts/deploy-host.sh`가 만드는 `fgc-http.conf`의 `rate=30r/m`, `10-guard.conf`의 `burst=10` | 이 스크립트는 `bundle.tgz`에 들어가므로 develop 병합 뒤 `dev-fgc`, `stage-fgc`, `release-fgc` 전체 경로다(2-1절) |

신한 재배포는 공개 웹을 멈췄다가 다시 올리고 DB 백업도 받으므로 **시연 중에는 돌리지 않는다.** Jenkins가 없으면 2-3절로 하되 그 체크아웃에 같은 변경이 있어야 한다. 서버에서 `$state/nginx/`를 직접 고쳐도 다음 배포가 저장소 값으로 덮어쓴다. 2-6절의 `previous/` 복원으로 #870 전 릴리스로 돌아가면 제한도 함께 빠진다. 실제 서버에서 돌린 기록은 없다.

---

## 2. 배포 재현과 롤백

### 2-1. 승격 경로

```text
develop 병합 → CI build, frontend → publish: ghcr.io/feegachu/fgc/{api,web}:sha-<커밋>
  → dev-fgc (자동, bundle.tgz + release.json 생성) → stage-fgc (SOURCE_BUILD: dev 빌드)
  → release-fgc (SOURCE_BUILD: stage 빌드, RFC_URL, PL 승인)
  → release-shinhan-fgc (SOURCE_BUILD: stage 빌드, PRD_URL, RFC_URL, PL 승인)
```

- `release.json`이 커밋 1개와 digest 2개를 고정한다. 이미지를 다시 빌드하지 않는다.
- 문서만 바뀐 push는 CI가 직전 이미지에 같은 digest로 태그만 더한다(`ci.yml`의 `Tag previous images`).

**문서 전용 커밋을 신한으로 승격하면 막힌다.** `make-release.py`는 이미지 라벨이 문서 전용 조상이면 허용하고 `image_revision`에 적지만, `shinhan_release.py`의 `verify_image`는 라벨을 `release["commit"]`과만 비교해 `pulled image revision differs from staging`으로 거절한다 [T23]. RC를 고를 때 `release.json`의 `commit`과 `image_revision`이 같은 빌드를 쓴다. 변경 전에 이미 막히므로 데이터 영향은 없다.

### 2-2. 신한 서버 배포

1. RC의 `stage-fgc` 빌드에서 검증을 끝낸다. RFC에 커밋, digest, Flyway 변경 유무를 적는다.
2. 데모 계정 보호를 먼저 끝낸다(4장). 안 하면 스크립트가 멈춘다.
3. Jenkins `release-shinhan-fgc`: `SOURCE_BUILD`, `PRD_URL=<PRD_URL>`, `RFC_URL`. 승인자는 PL.

스크립트 동작(`infra/scripts/deploy-shinhan.sh`): 이미지 pull → 라벨과 digest 검증 → nginx 템플릿 추출, 적응, `infra/shinhan/`의 `compose.yaml`, `nginx-guard.conf`, `nginx-http.conf` 복사 → `compose config` → 데모 계정 검사(4-4절) → 현재 설정 보관(`$release/previous/`) → `pg_dump`(`$state/backups/`, 경로는 `$release/db-backup-path.txt`) → 웹 중지 → `up -d db api` → health 대기 → 데모 계정 재검사 → `nginx -t` → 웹 교체 → 로컬과 공개 주소의 `/login` 200, 로그인 경로 401 확인 → `current.json` 기록. API가 뜨지 않으면 웹을 올리지 않고 멈춘다. DB는 자동으로 되돌리지 않는다. 배포 전 백업은 **DB만** 받는다. 파일 볼륨은 받지 않는다(9장).

### 2-3. Jenkins 없이 배포 (CI/CD 서버 다운)

새 빌드를 만들 수 없으므로 이미 승격한 묶음을 다시 적용하는 경우만 해당한다. PL 승인을 RFC에 남긴다.

```bash
# 신한: 저장소의 RC 커밋 체크아웃에서. promotion은 이전 릴리스에 남아 있다
ls -1dt ~/fgc-stg/releases/*/ | head
FGC_PUBLIC_URL=<PRD_URL> bash infra/scripts/deploy-shinhan.sh ~/fgc-stg/releases/<릴리스>/promotion.json

# AWS: 배포 계정으로 묶음을 푼 폴더에서
bash infra/scripts/deploy-host.sh release.json
```

스크립트 동작은 스텁 시험으로만 확인했다 [T22]. 실제 서버 수동 실행은 미확인, 리허설 때 확인한다.

### 2-4. 배포 검증

| 항목 | 명령 또는 화면 | 기대 |
|---|---|---|
| 재현 | 같은 digest 두 이미지로 기동 | 빈 DB 27초, 기존 DB 승격 24초 안에 `UP` [T1] (메모리 제한 격리 환경 값) |
| Flyway | `SELECT version, success FROM fgc.flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;` | 실패 행 없음. 순서 밖 적용 뒤에는 마지막 행이 최대 버전이 아닐 수 있다 |
| 차단 경로 | 공개 주소 `/actuator/health` | 404 [T1] |
| 업무 | 로그인(전 역할), 대시보드, 파일 다운로드, 배치 상태 | 트래커 7번 |
| 로그인 제한 앞단 경계 (신한) | 아래 측정 | 뒤쪽 요청이 429, VM 80번 직접 접속 불가. **미측정** |

**앞단 경계 측정 (PR #870 6절, 미측정).** 신한의 로그인 제한(1-6절)은 앞단이 `X-Forwarded-For`의 마지막 값에 접속자 주소를 넣고(덧붙이거나 덮어쓰고), VM 80번이 앞단에서만 열려 있다고 가정한다. 저장소 밖 사실인데 접속 권한이 없어 아직 보지 못했다. 배포 뒤 한 번 잰다. 잰 PC는 잠시 429를 볼 수 있으므로 시연 중에는 하지 않는다.

| 가정이 틀린 경우 | 결과 |
|---|---|
| 앞단이 헤더를 붙이지 않거나 보낸 값을 그대로 넘긴다 | 제한이 꺼질 뿐 로그인은 막히지 않는다 [T30] |
| 마지막 값이 모든 사용자에게 같다 (앞단이 자기 주소를 덧붙이는 경우 등) | **전원이 한 사용자로 세어져 분당 30회를 함께 쓴다.** 몰리면 모두 429를 본다 [T30] |
| VM 80번이 밖에서 열려 있다 | 앞단을 거치지 않고 헤더를 바꿔 제한을 피할 수 있다 |

1. 외부 PC에서 없는 계정으로 15회 로그인하되 요청마다 `X-Forwarded-For`를 다른 값으로 보낸다.

```bash
base=<PRD_URL>
for n in $(seq 1 15); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$base/api/v1/auth/login" \
    -H 'Content-Type: application/json' -H "Origin: $base" -H 'X-FGC-Client: web' \
    -H "X-Forwarded-For: 198.51.100.$n" -d '{"loginId":"__edge_probe__","password":"x"}'
done | tr '\n' ' '; echo
```

2. 서버의 web 로그 마지막 칸으로도 본다: `"${compose[@]}" logs --tail=20 web | grep auth/login`

| 응답 | web 로그 마지막 칸 | 뜻 |
|---|---|---|
| 뒤쪽 요청이 429 | 마지막 값이 잰 PC의 공인 주소: `"198.51.100.15, <잰 PC 주소>"` 또는 `"<잰 PC 주소>"` | 앞단이 덧붙이거나 덮어쓴다. 가정대로다 |
| 뒤쪽 요청이 429 | 마지막 값이 다른 주소 (앞단 주소 등) | 전원이 한 묶음일 수 있다. 4번으로 가른다 |
| 전부 401 | `"-"` | 앞단이 헤더를 붙이지 않는다. **제한이 꺼진 상태다** |
| 전부 401 | `"198.51.100.15"` | 앞단이 보낸 값을 그대로 넘긴다. **제한이 꺼진 상태다** |

3. 앞단을 거치지 않고 외부에서 VM 80번에 접속되는지 본다(`curl -m 5 -I http://<VM 공인 주소>/login`). 접속되면 안 된다. compose는 80번을 모든 인터페이스에 연다(`infra/shinhan/compose.yaml`의 `"80:80"`).
4. 잰 PC가 429를 보는 동안 다른 회선의 PC에서 정상 로그인이 되는지 본다. 그 PC도 429면 전원이 한 묶음이다.
5. 어긋나면 앞단 주소를 받아 `set_real_ip_from`으로 신뢰 경계를 넣거나(`real_ip_header X-Forwarded-For`, `real_ip_recursive on`, 제한 키 `$binary_remote_addr`. 설정 변경이 필요하고 미시험), 보안시험 결과서(`docs/FGC_보안시험_결과서_v1_0.md`, PR #858, 아직 미병합) 7절에 위험 수용 사유와 승인자를 적는다.

### 2-5. 롤백 판단

**이미지만 되돌린다고 DB가 돌아가지 않는다.** 앱이 뜨는 것과 안전한 것은 다르다.

| 상황 | 앱 기동 결과 | 판단 |
|---|---|---|
| 되돌릴 이미지와 현재 사이에 Flyway 변경 없음 | 정상 | 이전 digest만 다시 적용 |
| 현재 DB에 이전 이미지보다 **높은 번호**가 적용됨 | **뜬다.** Flyway가 `Schema "fgc" has a version (77) that is newer than the latest available migration` 경고만 남긴다 [T2] | 새 스키마 위에서 옛 코드가 돈다. 호환을 백엔드가 확인하지 못했으면 **백업 복원이 먼저다** |
| 현재 DB에 이전 이미지가 모르는 **낮은 번호**가 적용됨 (#836의 V66, V72, V73) | **뜨지 않는다.** `Detected applied migration not resolved locally: 66` 등으로 WAR 기동 실패, 컨테이너는 `Up`, health 404 [T7] | **백업 복원이 먼저다** [SRC-076] |

Flyway 변경 확인: `git diff --name-only <이전 커밋> <현재 커밋> -- src/main/resources/db/`

복원하면 백업 뒤에 쌓인 데이터가 사라진다. 확정, 기지급 자료가 끼어 있으면 PM이 손실 구간을 승인하고 forward fix와 비교해 고른다.

### 2-6. 롤백 명령

**AWS 운영:** `release-fgc`에서 `ROLLBACK=true`, `SOURCE_BUILD=<이전 운영 성공 빌드>`, `RFC_URL`. PL 승인. DB 복원이 필요하면 먼저 한다.

**신한, 복원 없이 (Flyway 변경 없을 때):** `release-shinhan-fgc`에 이전 `stage-fgc` 빌드 번호를 넣어 같은 절차로 승격한다.

**신한, 백업 복원 먼저 [T3, T7]:**

```bash
release=~/fgc-stg/releases/<되돌릴 릴리스>
"${compose[@]}" stop web api
"${compose[@]}" exec -T db pg_dump -U fgc -d fgc -Fc > "$state/backups/before-rollback-$(date -u +%Y%m%dT%H%M%SZ).dump"
"${compose[@]}" exec -T db pg_restore -U fgc -d fgc --clean --if-exists --single-transaction < "$(cat "$release/db-backup-path.txt")"
cp "$release/previous/compose.yaml" "$state/compose.yaml"
[ -f "$release/previous/release.env" ] && cp "$release/previous/release.env" "$state/release.env"
cp -R "$release/previous/nginx/." "$state/nginx/"
"${compose[@]}" up -d db api
until curl -fs http://127.0.0.1:18081/actuator/health | grep -q '"UP"'; do sleep 2; done
"${compose[@]}" run --rm -T --no-deps web nginx -t
"${compose[@]}" up -d --force-recreate --no-deps web
for f in current.json current-promotion.json; do [ -f "$release/previous/$f" ] && cp "$release/previous/$f" "$state/$f"; done
```

`--single-transaction`이라 복원이 실패하면 DB는 그대로 남는다. `compose` 변수는 `cp` 뒤에도 같은 경로를 가리키므로 다시 정의하지 않는다.

**검증:** Flyway 최신 버전이 백업 시점과 같다. `SELECT count(*) FROM fgc.flyway_schema_history WHERE version IN ('66','72','73');`이 0이다(#836 되돌림일 때). 건수, 로그인, 파일 다운로드를 본다.

**사후 기록:** 손실 구간을 1-4절에 적는다. 파일 볼륨은 이 절차로 돌아가지 않는다. DB가 모르는 파일이 남을 뿐 다운로드는 깨지지 않는다.

---

## 3. 일 배치 승인 복구

정책 원문은 운영정책서 '일일 변경분 처리' 절이다. 이 장이 그 절이 가리키는 "S44 Runbook의 완료 조건"이다 [SRC-065]. 후퇴를 자동으로 통제하는 API나 DB 장치는 없다(`V2_1` 주석: 후퇴 기록은 앱 책임). 그래서 아래 SQL이 통제를 대신한다.

### 3-1. 두 경로를 구분한다

`DailyChangedContractJob`은 `updated_at > 워터마크` **또는** 미처리 상태 사건이 있는 계약을 읽는다(`DailyChangedContractJobConfig`). 계약 하나가 실패해도 Step은 `COMPLETED`로 끝나고 워터마크가 실행 시작 시각으로 전진한다(`WatermarkAdvanceStepListener`).

| | 경로 A: 상태 사건 FAILED | 경로 B: `updated_at`만으로 선별된 계약 |
|---|---|---|
| 조건 | 같은 Job의 `SUCCEEDED`가 없는 사건이 있다 | 미처리 사건이 없다 |
| 다음 일 배치 | 다시 선별된다 | **다시 선별되지 않는다** [T8] |
| 복구 | 원인을 고치고 다음 실행의 사건 처리 성공을 확인 | 원인을 고치고 **이 장의 승인 복구** |

**CAP 수동 재검증으로 끝내지 않는다.** 계약 상세의 `[한도 재검증]`은 `cap_check`만 새로 만들고(`validation_run_id` 없음, `check_kind=MANUAL`), 차익거래와 스케줄은 하지 않으며 예외도 그대로다 [T9]. 원장은 이 배치의 범위가 아니라 월 검증 Step 6b로 본다 [SRC-065].

### 3-2. 탐지와 판별

탐지: `예외` 화면의 `일일 변경 계약 재검증 실패`, 또는 아래 SQL.

```sql
SELECT c.exception_case_id, c.contract_id, ic.contract_no, c.status, c.last_detected_run_id, c.detection_count,
       ic.updated_at, w.last_processed_at,
       EXISTS (SELECT 1 FROM fgc.contract_status_event e
                WHERE e.contract_id = c.contract_id
                  AND NOT EXISTS (SELECT 1 FROM fgc.contract_status_event_processing p
                                   WHERE p.contract_status_event_id = e.contract_status_event_id
                                     AND p.processing_job = w.job_name AND p.processing_status = 'SUCCEEDED')) AS has_pending_event,
       ic.updated_at <= w.last_processed_at AS behind_watermark,
       left(c.description, 120) AS reason
  FROM fgc.exception_case c
  JOIN fgc.insurance_contract ic USING (contract_id)
  JOIN fgc.batch_watermark w ON w.job_name = 'DailyChangedContractJob' AND w.step_name = 'changedContractStep'
 WHERE c.reason_code = 'CONTRACT_REVALIDATION_FAILED' AND c.status IN ('NEW', 'IN_REVIEW')
 ORDER BY c.last_detected_at DESC;
```

| 결과 | 판단 |
|---|---|
| `has_pending_event = t` | 경로 A. 후퇴하지 않는다 |
| `has_pending_event = f`, `behind_watermark = t` | 경로 B. 3-3절로 |
| `behind_watermark = f` | 다음 실행이 읽는다. 후퇴하지 않는다 |

### 3-3. 승인 전 확인

**담당:** 실행자 시스템 관리자, 승인자 GA관리자(1-5절).

1. 예외 번호, 계약 번호, 실패 실행 번호(`last_detected_run_id`), 실패 사유를 적는다.
2. 원인을 고친다. 룰셋 누락이면 정책 절차로 고친다. 고치지 않고 돌리면 같은 실패가 쌓인다 [T11].
3. 다시 처리될 범위를 본다. 그 계약의 `updated_at`보다 1초 앞까지 되돌리므로, 그 뒤에 바뀐 다른 계약도 함께 읽는다.

```sql
SELECT contract_id, contract_no, updated_at FROM fgc.insurance_contract
 WHERE updated_at > (SELECT updated_at - interval '1 second' FROM fgc.insurance_contract WHERE contract_id = <계약 번호>)
 ORDER BY updated_at;
```

4. GA관리자에게 위 내용으로 승인과 사유를 받아 `ticket`에 남긴다.
5. 01:30~02:30 KST(일 배치 02:00)를 피한다. 이 시간대 규칙은 가상 GA 정책으로 정함. 다시 열 조건: `fgc.batch.daily-changed-contract.cron` 변경.

### 3-4. 후퇴 실행

한 트랜잭션에서 잠금, 동시 실행 검사, 승인자 역할 검사, 예외, 계약, 실패 실행 연결 검사, 경로 판별, 후퇴, 감사 기록을 함께 한다. 하나라도 어긋나면 아무것도 바뀌지 않는다 [T10]. 기록되는 값: 승인자, 실행자, 사유, 티켓, 예외, 계약, 실패 실행 번호, 전후 워터마크, 재선별 건수와 번호, 시각.

```bash
psql_fgc <<'SQL'
\set exception_id '<예외 번호>'
\set contract_id '<계약 번호>'
\set failed_run_id '<실패 실행 번호>'
\set approver_login '<GA_ADMIN 로그인 ID>'
\set executor_login '<실행자 로그인 ID>'
\set ticket '<RFC 또는 이슈 댓글 번호>'
\set reason '<복구 사유>'
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('recovery.exception_id', :'exception_id', true),
       set_config('recovery.contract_id', :'contract_id', true),
       set_config('recovery.failed_run_id', :'failed_run_id', true),
       set_config('recovery.approver', :'approver_login', true),
       set_config('recovery.executor', :'executor_login', true),
       set_config('recovery.ticket', :'ticket', true),
       set_config('recovery.reason', :'reason', true) \g /dev/null
DO $recovery$
DECLARE
  c_job  CONSTANT text := 'DailyChangedContractJob';
  c_step CONSTANT text := 'changedContractStep';
  v_contract   bigint := current_setting('recovery.contract_id')::bigint;
  v_exception  bigint := current_setting('recovery.exception_id')::bigint;
  v_failed_run bigint := current_setting('recovery.failed_run_id')::bigint;
  v_before     timestamptz;
  v_updated    timestamptz;
  v_target     timestamptz;
  v_executor   bigint;
  v_reselect   bigint[];
BEGIN
  -- 워터마크 행을 잠가 Step 완료의 전진과 겹치지 않게 한다.
  SELECT last_processed_at INTO STRICT v_before FROM fgc.batch_watermark
   WHERE job_name = c_job AND step_name = c_step FOR UPDATE;
  -- 1. 동시 실행 차단
  IF EXISTS (SELECT 1 FROM fgc.validation_run
              WHERE run_type = 'MANUAL_CONTRACT' AND status IN ('CREATED', 'RUNNING'))
     OR EXISTS (SELECT 1 FROM fgc.batch_job_execution x JOIN fgc.batch_job_instance i USING (job_instance_id)
                 WHERE i.job_name = c_job AND x.status IN ('STARTING', 'STARTED', 'STOPPING')) THEN
    RAISE EXCEPTION '일 배치가 생성 또는 실행 중이다. 끝난 뒤 다시 한다';
  END IF;
  -- 2. GA 관리자 승인, 실행자 분리
  IF NOT EXISTS (SELECT 1 FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
                  WHERE u.login_id = current_setting('recovery.approver') AND r.role_code = 'GA_ADMIN') THEN
    RAISE EXCEPTION '승인자는 GA_ADMIN 역할 계정이어야 한다';
  END IF;
  SELECT user_id INTO v_executor FROM fgc.app_user WHERE login_id = current_setting('recovery.executor');
  IF v_executor IS NULL OR current_setting('recovery.approver') = current_setting('recovery.executor') THEN
    RAISE EXCEPTION '실행자는 승인자와 다른 등록 계정이어야 한다';
  END IF;
  -- 3. 예외, 계약, 실패 실행 연결
  IF NOT EXISTS (SELECT 1 FROM fgc.exception_case c
                   JOIN fgc.exception_occurrence o USING (exception_case_id)
                   JOIN fgc.validation_run r ON r.validation_run_id = o.validation_run_id
                  WHERE c.exception_case_id = v_exception AND c.contract_id = v_contract
                    AND c.reason_code = 'CONTRACT_REVALIDATION_FAILED' AND c.status IN ('NEW', 'IN_REVIEW')
                    AND o.validation_run_id = v_failed_run AND r.run_type = 'MANUAL_CONTRACT') THEN
    RAISE EXCEPTION '미처리 예외, 계약, 실패 실행이 서로 연결되지 않는다';
  END IF;
  -- 4. 미처리 상태 사건이 있으면 자동 재선별 경로다
  IF EXISTS (SELECT 1 FROM fgc.contract_status_event e
              WHERE e.contract_id = v_contract
                AND NOT EXISTS (SELECT 1 FROM fgc.contract_status_event_processing p
                                 WHERE p.contract_status_event_id = e.contract_status_event_id
                                   AND p.processing_job = c_job AND p.processing_status = 'SUCCEEDED')) THEN
    RAISE EXCEPTION '미처리 상태 사건이 있는 계약이다. 후퇴 없이 다음 실행에 다시 선별된다';
  END IF;
  -- 5. 뒤로만 옮기고, 그 계약이 다시 선별되는 만큼만 옮긴다
  SELECT updated_at INTO STRICT v_updated FROM fgc.insurance_contract WHERE contract_id = v_contract;
  IF v_updated > v_before THEN
    RAISE EXCEPTION '계약 변경 시각이 워터마크 뒤에 있어 후퇴가 필요 없다';
  END IF;
  v_target := v_updated - interval '1 second';
  -- 6. 함께 다시 처리될 범위를 감사기록에 남긴다
  SELECT coalesce(array_agg(contract_id ORDER BY contract_id), '{}') INTO v_reselect
    FROM fgc.insurance_contract WHERE updated_at > v_target;
  UPDATE fgc.batch_watermark SET last_processed_at = v_target WHERE job_name = c_job AND step_name = c_step;
  INSERT INTO fgc.audit_log (user_id, action_code, entity_type, entity_id, before_value, after_value, reason, request_id)
  VALUES (v_executor, 'BATCH_WATERMARK_REWIND', 'BATCH_WATERMARK', c_job || ':' || c_step,
          jsonb_build_object('last_processed_at', v_before),
          jsonb_build_object('last_processed_at', v_target,
                             'approved_by', current_setting('recovery.approver'),
                             'executed_by', current_setting('recovery.executor'),
                             'exception_case_id', v_exception, 'contract_id', v_contract,
                             'failed_validation_run_id', v_failed_run,
                             'reselected_contract_count', cardinality(v_reselect),
                             'reselected_contract_ids', to_jsonb(v_reselect[1:50])),
          current_setting('recovery.reason'), current_setting('recovery.ticket'));
  RAISE NOTICE 'watermark % -> %, reselect % contract(s)', v_before, v_target, cardinality(v_reselect);
END
$recovery$;
COMMIT;
SQL
```

성공하면 `NOTICE: watermark <전> -> <후>, reselect N contract(s)`가 나온다. 감사 번호를 적는다.

```sql
SELECT audit_log_id, occurred_at, before_value, after_value, reason, request_id
  FROM fgc.audit_log WHERE action_code = 'BATCH_WATERMARK_REWIND' ORDER BY audit_log_id DESC LIMIT 1;
```

### 3-5. 재선별 실행

정산담당자나 시스템 관리자가 `규제 검증 > 월 통합검증`에서 `실행 유형`을 `계약수동`으로 `[실행 생성]`하면 상세로 이동한다. 거기서 `[실행]`한다. 동시에 두 개는 만들어지지 않는다(`uq_validation_run_active_manual_contract`, `실행을 만들 수 없습니다.`) [T10]. 같은 JVM에서는 일 배치 launch도 단일 스레드라 한 번에 하나씩 돈다(`DailyChangedContractJobTrigger`).

### 3-6. 결과 확인

```bash
psql_fgc -At <<'SQL'
\set new_run <새 실행 번호>
\set contract_id <계약 번호>
\set exception_id <예외 번호>
SELECT 'run' AS kind, r.status AS a, r.current_step::text AS b, coalesce(r.failure_message, '') AS c
  FROM fgc.validation_run r WHERE r.validation_run_id = :new_run
UNION ALL SELECT 'step', s.status, s.read_count::text, s.write_count::text
  FROM fgc.batch_step_execution s
  JOIN fgc.batch_job_execution_params p ON p.job_execution_id = s.job_execution_id
 WHERE p.parameter_name = 'validationRunId' AND p.parameter_value = :'new_run' AND s.step_name = 'changedContractStep'
UNION ALL SELECT 'cap', payment_stage, result_status, as_of_date::text FROM fgc.cap_check
 WHERE validation_run_id = :new_run AND contract_id = :contract_id
UNION ALL SELECT 'arbitrage', payment_stage, result_status, as_of_date::text FROM fgc.arbitrage_check
 WHERE validation_run_id = :new_run AND contract_id = :contract_id
UNION ALL SELECT 'schedule', schedule_header_id::text, status, generated_at::text FROM fgc.schedule_header
 WHERE contract_id = :contract_id AND active_yn
UNION ALL SELECT 'redetected', count(*)::text, '', '' FROM fgc.exception_occurrence
 WHERE exception_case_id = :exception_id AND validation_run_id = :new_run;
SQL
```

| 행 | 기대 |
|---|---|
| `run` | `COMPLETED`, 8 |
| `step` | `COMPLETED`, 읽은 수가 3-3절 범위 건수와 같다 |
| `cap`, `arbitrage` | 이 실행 번호로 대상 단계 행이 있다 |
| `schedule` | 사용 중 스케줄이 있다. 변경이 없으면 새 헤더가 생기지 않는다 [T12] |
| `redetected` | **0** |

### 3-7. 종결 또는 유지

- **`redetected`가 0이고 위 행이 맞으면 종결한다.** `예외` 화면에서 `검토 시작`, 그다음 `해결`. `'처리 사유'`에 결과를, `'증빙'`에 `validation_run:<새 실행>,audit_log:<감사 번호>,ticket:<티켓>`을 적는다 [T12].
- **다시 실패하면 종결하지 않는다.** 예외는 미처리로 남고 `detection_count`가 늘며 `exception_occurrence`에 새 실행이 연결된다 [T11]. 원인을 다시 보고 3-3절부터 한다.

**사후 기록:** 1-4절. 예외, 감사, 실행 번호를 함께 적는다. 이슈 시나리오 1과 트래커 BE-03의 증거다. 지금 데이터로 재현되는 동작이며 인터페이스정의서 §7-5에 문서화돼 있다 [T8].

---

## 4. 데모 계정 보호

공개 저장소에 시드 비밀번호(`db/demo/V3` 머리 주석)가 있고, 신한 서버는 `db/demo`를 읽는다. 외부 공개 전에 `admin`, `gaadmin`, `settle01`, `audit01`, `agent01`을 보호한다 [SRC-065]. 시드 명세서 §5의 절차를 명령으로 옮긴 것이고, 거기서 말하는 S44 배포 검증이 4-3절이다. 이 문서에 비밀번호를 적지 않는다.

| 단계 | 내용 |
|---|---|
| 탐지 | 배포 전 점검, 배포 스크립트 |
| 초기 판단 | 5개 계정 행이 **있는지** 먼저 본다. 없는 계정의 401은 통과가 아니다 |
| 담당 | 실행 시스템 관리자, 승인 PM |
| 조치 | 4-2절 |
| 검증 | 4-3절 |
| 사후 기록 | 1-4절, 감사 행위 `DEMO_ACCOUNT_PROTECT` |

### 4-1. 보호 상태 보기

```sql
SELECT login_id, account_status, left(password_hash, 4) AS hash_kind, updated_at
  FROM fgc.app_user WHERE login_id IN ('admin', 'gaadmin', 'settle01', 'audit01', 'agent01') ORDER BY login_id;
```

5행이 나와야 한다. `password_hash = '!'`는 비밀번호 미지정 표식이고 로그인할 수 없다.

### 4-2. 비밀번호 변경 또는 잠금

새 비밀번호는 비밀 저장소에서 계정마다 따로 받는다. 해시는 표준 입력으로 만든다. 앱이 `$2y$` 해시를 받아들이는 것을 확인했다 [T5].

```bash
login_id=settle01      # 계정마다 반복
action=CHANGE          # CHANGE: 비밀번호 변경과 ACTIVE / LOCK: 잠금
read -rs -p "$login_id 새 비밀번호: " PW; echo
HASH=$(printf '%s' "$PW" | docker run --rm -i httpd:2.4-alpine htpasswd -inBC 12 fgc | cut -d: -f2 | tr -d '\r\n'); unset PW
{ cat <<SQL
\set login_id '$login_id'
\set action '$action'
\set hash '$HASH'
\set executor '<실행자>'
\set approver '<승인자>'
\set ticket '<티켓>'
\set reason 'SRC-065 공개 전 데모 계정 보호'
SQL
cat <<'SQL'
\set ON_ERROR_STOP on
BEGIN;
WITH target AS (
  SELECT user_id, login_id, account_status FROM fgc.app_user WHERE login_id = :'login_id' FOR UPDATE
), changed AS (
  UPDATE fgc.app_user u
     SET password_hash = CASE WHEN :'action' = 'CHANGE' THEN :'hash' ELSE u.password_hash END,
         account_status = CASE WHEN :'action' = 'CHANGE' THEN 'ACTIVE' ELSE 'LOCKED' END
    FROM target t WHERE u.user_id = t.user_id
  RETURNING u.account_status
), revoked AS (
  UPDATE fgc.auth_refresh_token r
     SET revoked_at = clock_timestamp(), revoked_reason = 'SUPERSEDED'
    FROM target t WHERE r.user_id = t.user_id AND r.revoked_at IS NULL
  RETURNING 1
)
INSERT INTO fgc.audit_log (action_code, entity_type, entity_id, before_value, after_value, reason, request_id)
SELECT 'DEMO_ACCOUNT_PROTECT', 'APP_USER', t.login_id,
       jsonb_build_object('account_status', t.account_status),
       jsonb_build_object('account_status', (SELECT account_status FROM changed), 'action', :'action',
                          'sessions_revoked', (SELECT count(*) FROM revoked), 'executed_by', :'executor', 'approved_by', :'approver'),
       :'reason', :'ticket'
  FROM target t;
COMMIT;
SQL
} | psql_fgc
unset HASH
```

그 계정의 열린 로그인도 함께 끊는다. 전에 받은 토큰은 바로 401이 된다 [T5]. `LOCK`이면 입력한 값은 쓰이지 않고 기존 해시가 남는다.

### 4-3. 옛 자격 증명 거절 검증

해시 검사는 **같은 공개 비밀번호를 새 salt로 다시 해시한 경우를 잡지 못한다** [T6]. 그래서 실제 로그인으로 확인한다. 계정이 없으면 실패로 친다. 배포 스크립트도 같은 확인을 한다(4-4절). 이 명령은 배포 밖에서, 외부 경로까지 확인할 때 쓴다.

```bash
base=<PRD_URL>
read -rs -p '공개 시드 비밀번호 (V3 머리 주석): ' OLD_PW; echo
export OLD_PW
for id in admin gaadmin settle01 audit01 agent01; do
  st=$(psql_fgc -At -c "SELECT account_status FROM fgc.app_user WHERE login_id = '$id'" </dev/null)
  [ -n "$st" ] || { echo "FAIL $id: 계정 없음. 401을 통과로 세지 않는다"; continue; }
  code=$(python3 -c 'import json,os,sys; print(json.dumps({"loginId": sys.argv[1], "password": os.environ["OLD_PW"]}))' "$id" \
    | curl -s -o /dev/null -w '%{http_code}' -X POST "$base/api/v1/auth/login" \
        -H 'Content-Type: application/json' -H "Origin: $base" -H 'X-FGC-Client: web' --data-binary @-)
  echo "$id status=$st old-credential=$code"
done
unset OLD_PW
```

**통과 조건:** 5개 모두 행이 있고, 모두 401이고, 잠근 계정은 `LOCKED`다. 하나라도 어긋나면 외부에 공개하지 않는다. 실패한 시도는 `LOGIN_FAIL`로 감사에 남고 비밀번호는 남지 않는다. 앱 로그에 평문이 없는 것을 확인했다 [T5]. 로그인 실패로 계정이 잠기는 기능은 없다.

### 4-4. 배포 단계 검사

#836(`9a0a37ca`)이 넣었고 행 존재 검사는 #862(`1b70467d`)가 더했다. **같은 검사를 다시 만들지 않는다.**

| 시점 | 검사 | 걸리면 |
|---|---|---|
| 업그레이드 전 | 5개 계정 중 저장소 시드 해시를 그대로 쓰는 `ACTIVE` 계정 (`shinhan_release.py unprotected-sql`) | 아무것도 바꾸지 않고 멈춘다. 계정 이름만 출력한다 |
| 업그레이드 전 | `agent01`이 아직 `'!'`인데 V66이 적용 전 | 멈춘다. V66이 공개 비밀번호로 켜는 것을 막는다 |
| API 기동 뒤, 웹 교체 전 | 같은 해시 검사 | 공개 웹을 멈추고 실패한다 |
| API 기동 뒤, 웹 교체 전 | 5개 계정의 행이 모두 있는가 (`missing-accounts-sql`, PR #862) | 공개 웹을 멈추고 실패한다. 없는 계정의 401은 거절의 증거가 아니다 |
| API 기동 뒤, 웹 교체 전 | 공개 시드 비밀번호로 5개 계정에 실제 로그인, 모두 401인가 | 공개 웹을 멈추고 실패한다. 요청 본문은 표준 입력으로만 보내 명령 인자와 로그에 비밀번호가 남지 않는다 |

격리 DB에서 SQL을 직접 돌려 보호 전 `admin,audit01,gaadmin,settle01`과 `agent01` 차단, 보호 뒤 빈 결과를 확인했다 [T4, T5]. 해시 검사만으로는 새 salt로 다시 해시한 공개 비밀번호를 잡지 못해 [T6] 로그인 거절을 함께 본다. 스크립트 시험과 행 존재 SQL 실행은 [T28].

### 4-5. 신한 서버의 agent01과 설계사 시연 (#836)

발표 서버가 신한으로 정해져(SRC-076) #836이 `db/demo-agent`(V66, V72, V73)와 `FGC_COMPARE_RULE_VERSION=compare-net-v1`을 compose에 넣었다.

1. 배포 **전에** 4-2절로 `agent01`에 Secret 비밀번호를 넣는다(`CHANGE`, `DISABLED`가 `ACTIVE`가 된다). 그러면 V66의 조건(`password_hash='!'`)이 맞지 않아 비밀번호를 덮어쓰지 않는다 [T7].
2. `release-shinhan-fgc`로 배포한다. 스크립트가 66, 72, 73이 없을 때만 그 실행에서 `FGC_FLYWAY_OUT_OF_ORDER=true`를 넘긴다. `.env`에 저장하지 않는다.
3. 확인: 세 버전 `success`, `agent01` 로그인, 포트폴리오, 다른 계약 403, 상품 추천 순위 `READY`, `compare-net-v1` [T7, T16, T27].
4. 그 뒤 재기동은 기본값 `false`로 뜬다 [T7].

되돌리기는 2-5절 세 번째 행이다.

### 4-6. 보호를 보장할 수 없으면

외부에 공개하지 않는다. 시연이 끝나 실제 운영으로 바꾸면 `db/demo`를 빼고 기준정보, 정책, 계정을 별도로 이관한다. 이미 데모가 적재된 DB는 위치를 바꿔도 계정이 없어지지 않으므로 4-1~4-3절을 다시 한다 [SRC-065, SRC-076]. 적용된 V3는 고치지 않는다 [SRC-027 D-04].

---

## 5. 키 교체와 AI 예산

### 5-1. 값이 실제로 들어갔는지 지문으로 확인한다

**Compose는 셸 환경변수를 `--env-file`보다 먼저 쓴다.** 셸에 `OPENAI_API_KEY`가 있으면 `.env`를 고쳐도 무시된다. 훈련 첫 시도에서 실제로 이 일이 있었다 [T19].

```bash
env | cut -d= -f1 | grep -E '^(OPENAI_API_KEY|SPRING_AI_OPENAI_API_KEY|DB_PASSWORD|JWT_SECRET|FGC_JWT_SECRET)$' && echo '셸에서 먼저 unset 한다'
# 값은 출력하지 않고 앞자리 지문만 비교한다
"${compose[@]}" exec -T api sh -c 'printf %s "$OPENAI_API_KEY" | sha256sum | cut -c1-12' </dev/null
grep '^OPENAI_API_KEY=' "$state/.env" | cut -d= -f2- | tr -d '\r\n' | sha256sum | cut -c1-12
```

### 5-2. AI 키

| 단계 | 내용 |
|---|---|
| 탐지 | 키 유출 신고, 만료 공지, `ai_call_log`의 `HTTP_401` |
| 담당 | PM(키 발급, 폐기), DevOps(반영) |
| 재기동 없이 | **새 키 반영은 안 된다.** `LlmGateway`가 시작할 때 클라이언트를 한 번 만든다. 할 수 있는 것은 옛 키 폐기뿐이다. 그러면 401이 나고 10분(`fgc.llm.cooldown`)씩 AI가 꺼지며 고정 안내로 답한다 |
| 재기동 있이 | 아래 명령. API만 다시 뜬다. 웹은 그대로이고 그동안 API 요청은 실패한다(훈련 값 49초) [T19] |
| 검증 | 5-1절 지문 일치, health `UP`, 질문 1건 뒤 `ai_call_log` 새 행. 실제 키 교체는 미확인, 리허설 때 확인 |

```bash
# 1. $state/.env 의 OPENAI_API_KEY 를 편집기로 바꾼다 (명령 인자로 넘기지 않는다)
"${compose[@]}" up -d --no-deps --force-recreate api
until curl -fs http://127.0.0.1:18081/actuator/health | grep -q '"UP"'; do sleep 2; done
```

AWS Pod는 Secret `fgc-app`의 `SPRING_AI_OPENAI_API_KEY`다. JSON 전체를 다시 만들어 교체하고 `systemctl --user restart fgc-app.service`로 재기동한다. 미확인, 리허설 때 확인.

### 5-3. JWT 서명키, DB 비밀번호

| 키 | 순서 | 영향 [T19] |
|---|---|---|
| `JWT_SECRET` | `.env` 수정 → API 재기동 (32바이트 이상. `prod`는 비면 기동 실패) | 옛 access token은 바로 401. 화면은 refresh cookie로 새 토큰을 받으므로 재로그인 없이 이어진다 |
| `DB_PASSWORD` | **역할 먼저:** 아래 SQL → `.env` 수정 → API 재기동 | 역할만 바꾼 동안 기존 연결은 유지된다. 다음 `up -d db api`(다음 배포 포함)는 `POSTGRES_PASSWORD` 환경이 달라져 **DB 컨테이너를 다시 만든다.** 데이터는 volume에 남고 API 연결은 회복된다 |

```bash
read -rs -p '새 DB 비밀번호: ' PW; echo
printf "ALTER ROLE fgc WITH PASSWORD '%s';\n" "$PW" | psql_fgc; unset PW
```

### 5-4. 쿼터 소진 대응, 규칙형 전환

**자동 동작** (`LlmGateway`, 인터페이스정의서 IF-EXT-03) [T16]:

| 상황 | `ai_call_log` | 사용자가 보는 것 |
|---|---|---|
| 첫 429 | `ERROR`, `HTTP_429_insufficient_quota` | 피코: 고정 안내 `지금 AI 답변을 확인할 수 없습니다.` (`source=FIXED`). 상품 추천 AI: 서버 값으로 쓴 규칙 문장 (`source=RULE`) |
| 그 뒤 10분 | `FALLBACK_RULE`, `AI_DISABLED`. **외부 호출 없음** | 같다 |
| 10분 뒤 | 한 번 다시 호출 | 소진이 그대로면 반복 |
| 키 비움 | `FALLBACK_RULE`, `NO_API_KEY` | 같다. 기동 로그에 `AI 기능을 끈 채로 기동` [T18] |
| 같은 사용자 분당 12회 초과 (#833) | `FALLBACK_RULE`, `RATE_LIMITED`. 외부 호출 없음 [T26] | 같다 |
| 답에 지시문이 되풀이됨 (#833) | `ERROR`, `PROMPT_ECHO` | 고정 안내 |

검사 순서는 키 없음, 쿨다운, 입력 크기, 호출 한도다. 시간 제한은 20초(`fgc.llm.timeout`, `FGC_LLM_TIMEOUT`)이고 시간 초과도 쿨다운을 건다 [SRC-075]. 계산, 확정, 업무 화면은 영향이 없다.

**수동 전환 절차:**

1. 탐지: 5-5절 SQL에서 `HTTP_429`가 보이거나 예산 임계값에 닿음.
2. 담당: PM이 결정, DevOps가 실행.
3. 조치: `$state/.env`의 `OPENAI_API_KEY=`를 비우고 API 재기동(5-2절 명령). 웹의 AI 진입 표시는 빌드 시점 값(`FGC_ASSISTANT_AI_ENABLED`)이라 런타임에 끌 수 없다. 화면은 남고 답이 고정 안내가 된다.
4. 검증: 5-1절 지문이 빈 값의 지문(`e3b0c44298fc`), 질문 1건 뒤 `NO_API_KEY`.
5. 사후 기록: 전환 시각, 그때까지 사용액, 알린 사람.
6. 되돌리기: 키를 넣고 재기동.

### 5-5. 예산 확인 (10달러)

주최 측 키의 총액은 10달러, 모델은 `gpt-4o-mini`, 단가는 입력 0.15달러, 출력 0.60달러(100만 토큰당)다 [SRC-060]. 모델을 바꾸면 단가도 바꾼다.

```sql
SELECT model_name,
       count(*) AS calls,
       count(*) FILTER (WHERE call_status = 'SUCCESS') AS success,
       count(*) FILTER (WHERE call_status = 'FALLBACK_RULE') AS fallback,
       count(*) FILTER (WHERE call_status IN ('ERROR', 'TIMEOUT')) AS failed,
       coalesce(sum(input_tokens), 0) AS input_tokens,
       coalesce(sum(output_tokens), 0) AS output_tokens,
       round(coalesce(sum(input_tokens), 0) * 0.15 / 1e6 + coalesce(sum(output_tokens), 0) * 0.60 / 1e6, 4) AS usd,
       round(10 - (coalesce(sum(input_tokens), 0) * 0.15 / 1e6 + coalesce(sum(output_tokens), 0) * 0.60 / 1e6), 4) AS usd_left
  FROM fgc.ai_call_log
 GROUP BY model_name;
```

일별, 용도별:

```sql
SELECT (requested_at AT TIME ZONE 'Asia/Seoul')::date AS day, purpose,
       count(*) AS calls,
       round(coalesce(sum(input_tokens), 0) * 0.15 / 1e6 + coalesce(sum(output_tokens), 0) * 0.60 / 1e6, 4) AS usd
  FROM fgc.ai_call_log
 GROUP BY 1, 2 ORDER BY 1 DESC, 4 DESC;
```

계산을 대조했다: 입력 4,000, 출력 1,000 토큰 호출 1,000건이 `1.2000`으로 SRC-060 계산과 같다 [T17]. `AiCallLogRepository` 주석의 한 줄 SQL은 토큰 행이 없으면 NULL을 돌려준다. 위 SQL은 0을 돌려준다.

**이 값은 하한이다.** 다음은 포함되지 않는다.

- 복원으로 사라진 행. 복원하면 `ai_call_log`도 스냅샷 시점으로 돌아간다. **복원 전에 합계를 9-3절 기록표에 옮긴다.**
- 다른 환경의 DB (개발계, 검증계, 로컬).
- CI 실호출 점검(`llm-live-check`, `pico-openai-smoke`, `pico-guide-api-smoke`).
- 시간 초과처럼 사용량을 받지 못한 호출.

주최 측 키의 사용량 화면을 볼 수 있는지는 근거 없음, 확인 필요다 [SRC-060].

| 누적 추정액 | 조치 |
|---|---|
| 5달러 | PM에게 알린다. CI 실호출 점검을 멈춘다 |
| 8달러 | 부스, 시연 외 호출을 멈춘다 |
| 9.5달러 | 5-4절 수동 전환 |

임계값은 가상 GA 정책으로 정함(업무 규칙 아님). 다시 열 조건: 주최 측이 총액이나 모델을 바꾸거나 실제 사용량 화면을 확인했을 때.

---

## 6. 배치 실패와 재시작

### 6-1. 무엇이 어떻게 멈췄나

| 증상 | 원인 | 절 |
|---|---|---|
| 월 실행 상태가 `FAILED` | Step 실패 | 6-2절 |
| `RUNNING`인데 진행하지 않음, 실행 번호가 늘지 않음 | 프로세스가 중간에 죽음 | 6-3절 |
| `[실패 지점부터 재시작]`이 꺼지고 `'이미 재시작 요청이 진행 중입니다.'` 또는 `'같은 월에 다른 실행이 진행 중입니다.'`가 오래 남음 | 재시작 접수 행이 활성으로 남음 | 6-3절 |
| 수동 차익 검증이나 일 배치가 `FGC-VRUN-001`로 막힘, `계약수동` 실행이 `CREATED`로 오래 남음 | 실패한 수동 차익 검증이 남긴 행 | 6-5절 |
| `계약수동` 실행 `FAILED` | 일 배치 Step 실패. 워터마크는 전진하지 않음 | 6-4절 |
| `계약수동` 실행 `COMPLETED`인데 예외가 생김 | 계약 단위 실패 | 3장 |

**탐지:** VRUN-W02 빨간 단계와 `'실패 사유'`, 1-3절 SQL. **담당:** 강호원, 정산담당자가 화면 실행. **FUN-045:** #844로 구현했고 #672는 닫혔다.

### 6-2. 월 실행 재시작

| 항목 | 지금 (코드 대조) |
|---|---|
| 경로 | `POST /api/v1/validation-runs/{id}/restart` (IF-API-82). `SETTLEMENT`, `SYSTEM_ADMIN`. 202는 접수이고 완료가 아니다. `execute`는 `CREATED`만 받고 `FAILED`는 409 `FGC-VRUN-004`다 (`ValidationRunExecuteServiceImpl`) |
| 화면 | VRUN-W02 `[실패 지점부터 재시작]` → 창 `'실패 지점 재시작 확인'` → `'재시작 사유'` 입력 → `[재시작 요청]`. 버튼은 서버 판정(`restartability`)이 허용할 때만 켜지고, 막힌 이유를 문구로 보인다. 결과는 `'재시도 이력'` 표에 남는다 |
| 입력 | 사유(1~500자), `Idempotency-Key`, 실패한 배치 실행 번호(`expectedBatchExecutionId`), 상태 버전(`expectedStateVersion`). 사유 말고는 화면이 채운다. 같은 키에 같은 내용이면 같은 접수를 돌려주고, 내용이 다르면 409다 |
| 재사용 | 같은 실행 번호, 같은 JobInstance의 새 JobExecution. 완료 Step은 건너뜀 |
| 동시 요청 | 월, 실행 잠금과 활성 요청 UNIQUE(`uq_validation_retry_active`, V80)로 하나만. 진 쪽은 `FGC-VRUN-005` |
| 접수 상태 | `validation_run_retry.state`: `ACCEPTED` → `LAUNCHING`(기동 스레드가 잡음) → `RUNNING`(JobExecution 시작, `batch_execution_id` 기록) → `SUCCEEDED` 또는 `FAILED`(`BATCH_FAILED`) |
| 기동 실패 | 대기열 포화 등은 503 `FGC-VRUN-008`, `FAILED`, `LAUNCH_FAILED`. 기동되지 않은 `ACCEPTED`는 5분 뒤 `FAILED`, `LAUNCH_TIMEOUT` (30초 주기 스케줄러) |
| 프로세스 중단 뒤 | `LAUNCHING`이나 `RUNNING`은 자동 실패로 추정하지 않는다. 6-3절 |

인터페이스정의서 §4-4-4가 정본이다. 그 절의 "PR #688 과도기" 표는 오른쪽 칸(정식 계약)이 지금 동작이다. `MANUAL_CONTRACT`는 월 재시작으로 보내지 않는다(6-4절).

**조치:** 실패 사유를 읽고 원인을 고친 뒤 재시작한다. 사유에 요청번호가 있으면(PR #879 `561b37b5`부터의 새 실패) 사유에는 예외 원문이 없으니 서버 로그에서 `requestId=<요청번호>`를 찾아 본다. 로그가 회전해 없으면 아래 SQL로 Spring Batch가 저장한 예외를 본다. 앞 2,500자만 남으니 재배포 전에 로그를 받아 두는 편이 낫다. 고치지 않으면 같은 곳에서 다시 실패한다 [T13]. 성공해도 자동으로 확정되지 않는다.

```sql
SELECT s.job_execution_id, s.step_name, s.exit_message
  FROM fgc.batch_step_execution s
  JOIN fgc.batch_job_execution_params p USING (job_execution_id)
 WHERE p.parameter_name IN ('requestId', 'restartRequestId') AND p.parameter_value = '<요청번호>'
   AND s.status = 'FAILED'
 ORDER BY s.step_execution_id DESC;
```

이 SQL은 `MonthlyJournalRestartIntegrationTest`에 임시 조회를 넣어 PR #879 `ab88aa52`로 확인했다(커밋 안 함). 25번째 분개 실패에서 사유는 사용자 문장이고 `exit_message`에 예외 원문이 있었다.

**검증 (앞의 두 SQL은 [T13], 마지막 SQL은 코드 대조):**

```sql
SELECT x.job_execution_id, x.status, i.job_instance_id
  FROM fgc.batch_job_execution x JOIN fgc.batch_job_instance i USING (job_instance_id)
 WHERE i.job_name = 'MonthlyValidationJob' ORDER BY 1 DESC LIMIT 5;
SELECT step_name, status, job_execution_id FROM fgc.batch_step_execution
 WHERE job_execution_id = (SELECT max(job_execution_id) FROM fgc.batch_job_execution) ORDER BY step_execution_id;
SELECT retry_id, state, previous_batch_execution_id, batch_execution_id, failed_step, error_code, requested_at, finished_at
  FROM fgc.validation_run_retry WHERE validation_run_id = <실행 번호> ORDER BY retry_id DESC;
```

같은 `job_instance_id`에 새 실행이 생기고 실패한 Step부터만 있어야 한다. `validation_run`은 `COMPLETED`, 8이다. 접수 행은 `SUCCEEDED`다. 감사에 `VALIDATION_RUN_RETRIED`가 남고 재시작 사유는 `validation_run_retry.reason`에 있다. T13은 #844 전 이미지의 `execute` 경로로 돌린 기록이다. `/restart` 경로는 `MonthlyJournalRestartIntegrationTest`(30건 중 25번째 실패 뒤 재시작), `ValidationRestartIntegrationTest`(동시 요청, 멱등, 기동 실패)가 PostgreSQL로 보고, 격리 환경에서 앱을 띄워 돌린 기록은 [T31]이다. 실제 서버는 리허설 때 확인한다. 11/2 감사가 지적한 "정식 재시작 없음"은 #844로 해소됐다.

### 6-3. RUNNING 고착

API가 죽으면 `validation_run`은 `RUNNING`, `batch_job_execution`은 `STARTED`로 남는다. 자동 복구는 없다. 그동안 `[실행]`은 `상태를 변경할 수 없습니다.`, 새 실행은 `이번 달 검증이 이미 진행 중입니다.`로 막힌다 [T14]. 전날의 `계약수동` 실행이 고착되면 그날 02:00 일 배치도 새 실행을 만들지 못해 실패한다(`uq_validation_run_active_manual_contract`).

**초기 판단:** 살아 있는 처리가 아닌지 먼저 확인한다. API 시작 시각이 실행 시작 시각보다 늦어야 한다.

```bash
docker inspect -f '{{.State.StartedAt}}' fgc-shinhan-api-1
```

```sql
SELECT x.job_execution_id, i.job_name, x.status, x.start_time
  FROM fgc.batch_job_execution x JOIN fgc.batch_job_instance i USING (job_instance_id)
 WHERE x.status IN ('STARTING', 'STARTED', 'STOPPING');
```

**담당:** 실행 시스템 관리자, 승인 GA관리자. **조치:** PM 결정, 팀 공유 전 (1-5절). `validation_run`의 상태 트리거가 `RUNNING → FAILED`를 허용한다(`guard_run_lifecycle`).

```bash
psql_fgc <<'SQL'
\set run_id '<실행 번호>'
\set approver_login '<GA_ADMIN 로그인 ID>'
\set executor_login '<실행자 로그인 ID>'
\set ticket '<티켓>'
\set reason '<사유>'
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('recovery.run_id', :'run_id', true), set_config('recovery.approver', :'approver_login', true),
       set_config('recovery.executor', :'executor_login', true), set_config('recovery.ticket', :'ticket', true),
       set_config('recovery.reason', :'reason', true) \g /dev/null
DO $recovery$
DECLARE
  v_run bigint := current_setting('recovery.run_id')::bigint;
  v_type text; v_month date; v_no int; v_before text; v_executor bigint; v_exec bigint[];
BEGIN
  SELECT run_type, validation_month, run_no, status INTO STRICT v_type, v_month, v_no, v_before
    FROM fgc.validation_run WHERE validation_run_id = v_run FOR UPDATE;
  IF v_before <> 'RUNNING' THEN RAISE EXCEPTION 'RUNNING 실행만 대상이다 (현재 %)', v_before; END IF;
  SELECT user_id INTO v_executor FROM fgc.app_user WHERE login_id = current_setting('recovery.executor');
  IF v_executor IS NULL OR NOT EXISTS (SELECT 1 FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
       WHERE u.login_id = current_setting('recovery.approver') AND r.role_code = 'GA_ADMIN')
     OR current_setting('recovery.approver') = current_setting('recovery.executor') THEN
    RAISE EXCEPTION '승인자는 GA_ADMIN, 실행자는 승인자와 다른 등록 계정이어야 한다';
  END IF;
  SELECT coalesce(array_agg(x.job_execution_id), '{}') INTO v_exec
    FROM fgc.batch_job_execution x
    JOIN fgc.batch_job_instance i USING (job_instance_id)
   WHERE x.status IN ('STARTING', 'STARTED', 'STOPPING')
     AND i.job_name = CASE WHEN v_type = 'MANUAL_CONTRACT' THEN 'DailyChangedContractJob' ELSE 'MonthlyValidationJob' END
     AND EXISTS (SELECT 1 FROM fgc.batch_job_execution_params p WHERE p.job_execution_id = x.job_execution_id
                  AND p.parameter_name = 'validationMonth' AND p.parameter_value = to_char(v_month, 'YYYY-MM'));
  UPDATE fgc.batch_step_execution SET status = 'FAILED', exit_code = 'FAILED', end_time = clock_timestamp(), last_updated = clock_timestamp()
   WHERE job_execution_id = ANY (v_exec) AND status IN ('STARTING', 'STARTED', 'STOPPING');
  UPDATE fgc.batch_job_execution SET status = 'FAILED', exit_code = 'FAILED', end_time = clock_timestamp(), last_updated = clock_timestamp(),
         exit_message = 'Runbook: 프로세스 중단 뒤 실패로 표시'
   WHERE job_execution_id = ANY (v_exec);
  UPDATE fgc.validation_run SET status = 'FAILED', failure_message = '운영 복구: 프로세스 중단 뒤 실패로 표시'
   WHERE validation_run_id = v_run;
  INSERT INTO fgc.audit_log (user_id, action_code, entity_type, entity_id, before_value, after_value, reason, request_id)
  VALUES (v_executor, 'VALIDATION_RUN_FORCED_FAILED', 'validation_run', v_run::text,
          jsonb_build_object('status', v_before),
          jsonb_build_object('status', 'FAILED', 'job_execution_ids', to_jsonb(v_exec),
                             'approved_by', current_setting('recovery.approver'), 'executed_by', current_setting('recovery.executor')),
          current_setting('recovery.reason'), current_setting('recovery.ticket'));
END
$recovery$;
COMMIT;
SQL
```

**검증 [T14]:** 상태 `FAILED`, 배치 실행 `FAILED`. `계약수동`은 새 실행을 만든다. 감사 행위 `VALIDATION_RUN_FORCED_FAILED`. 월 실행은 `[실패 지점부터 재시작]`으로 실패 Step부터 이어 간다(6-2절). T14는 #844 전 이미지라 재시작을 `execute`로 돌렸다. 이 SQL 뒤의 `/restart`는 [T31]로 확인했다. 화면 사유는 `운영 복구: 프로세스 중단 뒤 실패로 표시`이고 티켓은 감사 `request_id`에 남는다. PR #879부터 영문 3자 이상이 든 사유 문장은 응답에서 빠져(`ValidationFailureText`, 화면정의서 공통규칙 10, [SRC-074]) 이 문구에 영문이나 티켓을 넣지 않는다. T14는 옛 문구로 돌렸고 그 뒤 문구만 바꿨다.

**재시작 도중에 죽었을 때 [T31].** `validation_run_retry`에 활성 행이 남는다. 남아 있으면 그 실행의 버튼은 `'이미 재시작 요청이 진행 중입니다.'`로, 같은 달 다른 실행의 버튼은 `'같은 월에 다른 실행이 진행 중입니다.'`로 꺼진다. 같은 달의 새 `월간` 실행은 `FGC-VRUN-001`, 확정은 `FGC-VRUN-005`로 막힌다(`hasActiveRetryForMonth`).

```sql
SELECT r.retry_id, r.validation_run_id, r.state, v.status AS run_status, r.previous_batch_execution_id, r.batch_execution_id, r.requested_at, r.started_at
  FROM fgc.validation_run_retry r JOIN fgc.validation_run v USING (validation_run_id)
 WHERE r.state IN ('ACCEPTED', 'LAUNCHING', 'RUNNING');
```

| 접수 `state` | 실행 `run_status` | 조치 |
|---|---|---|
| `ACCEPTED` | `FAILED` | API가 다시 뜨면 스케줄러가 접수 5분 뒤 `FAILED`, `LAUNCH_TIMEOUT`으로 닫는다. 기다린다 |
| `LAUNCHING` | `FAILED` | 아래 SQL |
| `RUNNING` | `RUNNING` | 앞의 멈춘 실행 SQL(`run_id`)로 실행을 `FAILED`로 표시한 **뒤에** 아래 SQL. 멈춘 실행 SQL은 이 행을 고치지 않고, 아래 SQL은 `RUNNING` 실행을 거절한다 |
| `RUNNING` | `FAILED` | 아래 SQL. 멈춘 실행 SQL은 `RUNNING` 실행만 받는다 |
| `RUNNING` | `COMPLETED` | 아래 SQL. 처리는 끝났고 마지막 기록만 못 한 경우라 접수를 `SUCCEEDED`로 닫는다 |

**담당:** 실행 시스템 관리자, 승인 GA관리자. **조치:** PM 결정, 팀 공유 전 (1-5절). 다시 열 조건: 기동 시 자동 정리를 도입할 때. 위 초기 판단대로 API 시작 시각이 `requested_at`보다 늦은지 먼저 본다. 이 SQL은 접수 행과 그 실행에 남은 활성 배치 실행을 함께 닫는다. 배치 실행이 `STARTING`이나 `STARTED`로 남으면 서버가 재시작을 허용하지 않기 때문이다(`MonthlyJobExecutionLookup`).

```bash
psql_fgc <<'SQL'
\set retry_id '<접수 번호>'
\set approver_login '<GA_ADMIN 로그인 ID>'
\set executor_login '<실행자 로그인 ID>'
\set ticket '<티켓>'
\set reason '<사유>'
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('recovery.retry_id', :'retry_id', true), set_config('recovery.approver', :'approver_login', true),
       set_config('recovery.executor', :'executor_login', true), set_config('recovery.ticket', :'ticket', true),
       set_config('recovery.reason', :'reason', true) \g /dev/null
DO $recovery$
DECLARE
  v_retry bigint := current_setting('recovery.retry_id')::bigint;
  v_state text; v_run bigint; v_type text; v_month date; v_no int; v_by bigint; v_status text; v_executor bigint; v_exec bigint[];
  v_done boolean; v_next text; v_batch text;
BEGIN
  SELECT state, validation_run_id INTO STRICT v_state, v_run FROM fgc.validation_run_retry WHERE retry_id = v_retry FOR UPDATE;
  IF v_state NOT IN ('LAUNCHING', 'RUNNING') THEN RAISE EXCEPTION 'LAUNCHING, RUNNING 접수만 대상이다 (현재 %)', v_state; END IF;
  SELECT run_type, validation_month, run_no, triggered_by, status INTO STRICT v_type, v_month, v_no, v_by, v_status
    FROM fgc.validation_run WHERE validation_run_id = v_run FOR UPDATE;
  IF v_status NOT IN ('FAILED', 'COMPLETED') THEN
    RAISE EXCEPTION '실행이 % 상태다. RUNNING이면 멈춘 실행 SQL로 먼저 FAILED로 표시한다', v_status;
  END IF;
  SELECT user_id INTO v_executor FROM fgc.app_user WHERE login_id = current_setting('recovery.executor');
  IF v_executor IS NULL OR NOT EXISTS (SELECT 1 FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
       WHERE u.login_id = current_setting('recovery.approver') AND r.role_code = 'GA_ADMIN')
     OR current_setting('recovery.approver') = current_setting('recovery.executor') THEN
    RAISE EXCEPTION '승인자는 GA_ADMIN, 실행자는 승인자와 다른 등록 계정이어야 한다';
  END IF;
  -- 이 실행의 JobInstance에 남은 활성 배치 실행. 파라미터 네 개로 찾는다(MonthlyJobExecutionLookup과 같다).
  SELECT coalesce(array_agg(x.job_execution_id), '{}') INTO v_exec
    FROM fgc.batch_job_execution x JOIN fgc.batch_job_instance i USING (job_instance_id)
   WHERE i.job_name = 'MonthlyValidationJob' AND x.status IN ('STARTING', 'STARTED', 'STOPPING')
     AND (SELECT count(*) FROM fgc.batch_job_execution_params p WHERE p.job_execution_id = x.job_execution_id
           AND (p.parameter_name, p.parameter_value) IN (('validationMonth', to_char(v_month, 'YYYY-MM')), ('runNo', v_no::text),
                                                         ('runType', v_type), ('triggeredBy', v_by::text))) = 4;
  v_done := v_status = 'COMPLETED';
  v_next := CASE WHEN v_done THEN 'SUCCEEDED' ELSE 'FAILED' END;
  v_batch := CASE WHEN v_done THEN 'COMPLETED' ELSE 'FAILED' END;
  UPDATE fgc.batch_step_execution SET status = 'FAILED', exit_code = 'FAILED', end_time = clock_timestamp(), last_updated = clock_timestamp()
   WHERE job_execution_id = ANY (v_exec) AND status IN ('STARTING', 'STARTED', 'STOPPING');
  UPDATE fgc.batch_job_execution SET status = v_batch, exit_code = v_batch, end_time = clock_timestamp(), last_updated = clock_timestamp(),
         exit_message = 'Runbook: 재시작 접수 강제 종료'
   WHERE job_execution_id = ANY (v_exec);
  UPDATE fgc.validation_run_retry SET state = v_next, finished_at = clock_timestamp(),
         error_code = CASE WHEN v_done THEN NULL ELSE 'RUNBOOK_FORCED_FAILED' END
   WHERE retry_id = v_retry;
  INSERT INTO fgc.audit_log (user_id, action_code, entity_type, entity_id, before_value, after_value, reason, request_id)
  VALUES (v_executor, 'VALIDATION_RUN_RETRY_FORCED_CLOSED', 'VALIDATION_RUN_RETRY', v_retry::text,
          jsonb_build_object('state', v_state, 'validation_run_id', v_run, 'run_status', v_status),
          jsonb_build_object('state', v_next, 'job_execution_ids', to_jsonb(v_exec),
                             'approved_by', current_setting('recovery.approver'), 'executed_by', current_setting('recovery.executor')),
          current_setting('recovery.reason'), current_setting('recovery.ticket'));
END
$recovery$;
COMMIT;
SQL
```

**검증 [T31]:** 접수 행이 `FAILED`와 `RUNBOOK_FORCED_FAILED`로 닫힌다(`COMPLETED` 실행이면 `SUCCEEDED`). 감사에 `VALIDATION_RUN_RETRY_FORCED_CLOSED`가 남는다. 위 조회 SQL이 0행이다. `[실패 지점부터 재시작]`이 켜지고, 재시작하면 닫은 배치 실행을 기준으로 같은 JobInstance에서 이어 간다. 같은 달의 새 실행과 확정이 `FGC-VRUN-001`, `FGC-VRUN-005`로 막히지 않는다. `validation_run`은 이 SQL이 고치지 않는다. 격리 환경 기록이고 실제 서버에서 돌린 기록은 없다.

스냅샷 전에는 활성 실행이 없는지 먼저 본다(9-1절). 활성 실행이 든 스냅샷을 복원하면 같은 고착이 생긴다.

### 6-4. 일 배치 Step 실패

Step이 `FAILED`면 워터마크는 전진하지 않아 다음 실행이 같은 구간을 다시 읽는다(`WatermarkAdvanceStepListener`). `계약수동` 실행은 재시작 대상이 아니다. 화면에 재시작 버튼이 없고 서버도 거절한다(`execute`는 `FGC-VRUN-004`, `/restart`는 `FGC-VRUN-005`). 원인을 고치고 새 실행을 만들거나 다음 02:00을 기다린다. 이 경로는 코드 대조만 했다. Step 실패 주입은 월 실행으로 시험했다 [T13].

자동 실행을 끄는 설정 `fgc.batch.daily-changed-contract.enabled`가 있지만 compose에 넘기는 변수가 없다. 배치를 멈춰야 하면 API를 멈춘다.

### 6-5. `계약수동` 실행이 `CREATED`로 남음

수동 차익 검증(IF-API-33)은 실행 행을 별도 트랜잭션으로 먼저 커밋한다. #871 전 코드는 검증이 실패해도 이 행을 남겼다. #871 뒤에는 실패한 검증이 자기 행을 지우지만, 지우기까지 실패하면 `afterCompletion threw exception` 오류 로그만 남고 행은 그대로다.

활성 `계약수동` 실행은 전역 1건이라(V13) 이 행이 있으면 모든 수동 차익 검증이 `FGC-VRUN-001`로 막힌다. 02:00 일 배치도 막힌다. 일 배치는 그날 만든 행만 이어받고, 그 전 행이 남아 있으면 새 실행을 만들다 같은 오류로 실패한다 [코드 대조, `CreateDailyRunTasklet`].

**탐지:** 화면의 `실행을 만들 수 없습니다.` 안내, 일 배치 실패 로그, 아래 SQL. **#871을 배포한 뒤 한 번 확인한다.** 그 전에 남은 행은 배포해도 사라지지 않는다.

```sql
SELECT validation_run_id, status, triggered_by, created_at FROM fgc.validation_run
 WHERE run_type = 'MANUAL_CONTRACT' AND status IN ('CREATED', 'RUNNING');
```

**초기 판단:** `RUNNING`이면 6-3절이다. `CREATED`는 6-3절 SQL이 거절하고 상태 트리거도 `CREATED → FAILED`를 막는다(`guard_run_lifecycle`). 방금 만든 행은 진행 중인 수동 검증일 수 있다. 검증이 끝나기 전까지 다른 세션에는 `CREATED`로 보이므로 몇 분 뒤 다시 본다.

**담당:** 정산담당자가 화면 실행, 강호원 확인. **조치:** VRUN-W01에서 그 `계약수동` 실행을 열고(VRUN-W02) `[실행]`을 누른다. 일 배치가 그 행으로 바로 돈다. 오늘 만든 행이 아니어도 된다. DB를 손으로 고치지 않는다. 워터마크 뒤의 변경 계약이 이때 처리되므로 시연 중이면 끝난 뒤에 누른다.

**검증 [T29]:** 상태가 `COMPLETED`가 되고 수동 차익 검증이 다시 된다. `FAILED`로 끝나도 활성 행은 풀린다. 그 실패는 6-4절로 본다. 화면 버튼과 일 배치 경로는 코드 대조만 했다. 사후 기록은 1-4절.

---

## 7. 상태 사건 후속 작업 소진

`contract_event_job.attempt_count`가 `ContractStatusFollowUpService.MAX_ATTEMPTS`(5)에 닿으면 자동으로 잡히지 않고, **같은 계약의 뒤 사건도 함께 멈춘다** (`ContractEventJobRepository.claimNext`). 처리 중단으로 실패 기록 없이 끝나면 상태만으로 소진을 알 수 없으므로 시도 횟수로 판단한다(#719 코멘트).

새 API를 만들지 않고 이 절차로 다룬다. **PM 결정, 팀 공유 전.** 다시 열 조건: 운영에서 소진이 반복되거나 운영 담당이 DB 접근 없이 재시도해야 할 때(인터페이스정의서 §4-4-2).

**처리기 (#831, #835, 코드 대조).** 사건을 저장하는 트랜잭션에서 아래 조건에 맞는 처리기마다 작업 1행이 생긴다. 스케줄러가 10초마다 잡아 실패하면 1, 2, 4, 8분 뒤 다시 시도하고, 5번째 시도가 끝나면 소진이다(첫 시도부터 약 15분).

| 작업 (`processing_job`) | 화면 이름 | 작업이 생기는 사건 | 하는 일 |
|---|---|---|---|
| `PaymentHoldJob` (`PaymentHoldFollowUpProcessor`) | `지급 보류 처리` | 새 상태가 `UNPAID`, `LAPSED`, `TERMINATED`, `MATURED`, `CANCELLED`, 또는 `UNPAID` → `ACTIVE` | 미확정 지급 건에 `payment_hold`, 회차를 `HOLD`나 `CANCELLED`로. `UNPAID` → `ACTIVE`면 미납으로 건 보류와 회차를 푼다 |
| `PaymentResumeJob` (`PaymentResumeFollowUpProcessor`) | `부활 재개 처리` | 새 상태가 `REVIVED` | 정책이 허용한 회차를 `PLANNED`로, 그 지급 건 보류를 해제. 자동으로 확정하지 않는다 |

그 밖의 사건은 작업이 없다(화면 `대상 없음`).

**소진하면 막히는 것.** 그 사건의 보류나 재개가 반영되지 않고, **그 계약에 끝나지 않은 작업이 있는 동안 지급 확정과 스케줄 확정이 `FGC-IMPL-PAYMENT_HELD`로 거절된다** (`PaymentHoldGate`의 `FOLLOW_UP_PENDING`, `PaymentHoldFollowUpIntegrationTest`). 환수(차감) 건은 대상이 아니다.

| 단계 | 내용 |
|---|---|
| 탐지 | 아래 SQL. 계약 상세 `기본정보` 탭 `'상태이력'` 표의 `'후속 상태'`가 `자동 재시도 소진`이고 표 위에 `'자동 재시도를 모두 쓴 후속 처리가 있습니다. 이 계약의 뒤 사건도 함께 멈춥니다. 운영 담당 확인이 필요합니다.'`. 확정 화면의 `'확정할 수 없습니다. 계약 상태 사건으로 보류된 지급 건이거나 후속 처리가 끝나지 않았습니다.'`로 먼저 드러나기도 한다 |
| 초기 판단 | 실패 사유를 읽는다. 원문이 더 필요하면 서버 로그에서 `eventId=<사건 번호>`를 찾는다. 스케줄러가 돌리는 작업이라 요청번호가 없다. 뒤에 기다리는 사건이 있는지 본다. 화면이 `처리 중`이면 마지막 시도가 끝나지 않은 것이니 기다린다(아래 SQL도 이 행을 뺀다). `FGC-IMPL-POLICY_MISSING`이면 계약일에 적용할 상태별 지급조건 정책을 찾지 못한 것이다 |
| 담당 | 실행 시스템 관리자, 승인 GA관리자, 원인은 해당 처리기 담당 |
| 조치 | 원인을 고치고 아래 SQL. 정책 누락은 정책 절차로 고친다 |
| 검증 | 아래 표 |
| 사후 기록 | 1-4절, 감사 행위 `CONTRACT_EVENT_JOB_MANUAL_RETRY` |

```sql
SELECT j.contract_status_event_id, j.processing_job, j.state, j.attempt_count, j.error_code, j.last_attempt_at,
       e.contract_id, e.event_seq, e.new_status,
       (SELECT left(p.failure_reason, 200) FROM fgc.contract_status_event_processing p
         WHERE p.contract_status_event_id = j.contract_status_event_id AND p.processing_job = j.processing_job
           AND p.processing_status = 'FAILED' ORDER BY p.processed_at DESC LIMIT 1) AS last_failure,
       (SELECT count(*) FROM fgc.contract_event_job n JOIN fgc.contract_status_event ne USING (contract_status_event_id)
         WHERE ne.contract_id = e.contract_id AND ne.event_seq > e.event_seq AND n.state <> 'SUCCEEDED') AS waiting_behind
  FROM fgc.contract_event_job j
  JOIN fgc.contract_status_event e USING (contract_status_event_id)
 WHERE j.state <> 'SUCCEEDED' AND j.attempt_count >= 5
   -- 마지막 시도가 아직 처리 중인 행은 뺀다 (화면의 처리 중과 같은 판정, ContractEventJobRepository.findByContract)
   AND NOT (j.next_retry_at > clock_timestamp()
            AND NOT EXISTS (SELECT 1 FROM fgc.contract_status_event_processing p
                             WHERE p.contract_status_event_id = j.contract_status_event_id AND p.processing_job = j.processing_job
                               AND p.processing_status = 'FAILED' AND p.processed_at >= j.last_attempt_at))
 ORDER BY e.contract_id, e.event_seq;
```

**트리거 제약:** `contract_event_job`에는 트리거가 없어 갱신할 수 있다(#831, #835도 추가하지 않았다). `contract_status_event_processing`, `contract_status_event_effect`, `audit_log`는 append-only라 고치거나 지우지 않는다. `payment_hold`는 해제 1회 말고는 바꾸거나 지울 수 없다(`trg_payment_hold_change`, V79). 보류는 처리기로만 풀고 손으로 풀지 않는다. 처리기 결과, 영향 기록, 성공 이력, 완료 표시는 한 트랜잭션이라(`ContractStatusFollowUpService.process`) 실패한 시도의 영향은 남지 않고, 성공은 작업당 1행이다(`uq_cse_processing_succeeded`).

**재시도.** 시도 횟수를 한도 바로 아래(4)로 내려 **자동 재시도를 정확히 1번** 더 허용한다. 처리는 10초 주기 스케줄러가 한다. API 호출이 필요 없다.

```bash
psql_fgc <<'SQL'
\set event_id '<사건 번호>'
\set job '<PaymentHoldJob 또는 PaymentResumeJob>'
\set approver_login '<GA_ADMIN 로그인 ID>'
\set executor_login '<실행자 로그인 ID>'
\set ticket '<티켓>'
\set reason '<사유>'
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('recovery.event_id', :'event_id', true),
       set_config('recovery.job', :'job', true),
       set_config('recovery.approver', :'approver_login', true),
       set_config('recovery.executor', :'executor_login', true),
       set_config('recovery.ticket', :'ticket', true),
       set_config('recovery.reason', :'reason', true) \g /dev/null
DO $recovery$
DECLARE
  c_max      CONSTANT int := 5; -- ContractStatusFollowUpService.MAX_ATTEMPTS
  v_event    bigint := current_setting('recovery.event_id')::bigint;
  v_job      text   := current_setting('recovery.job');
  v_state    text;
  v_attempts int;
  v_error    text;
  v_contract bigint;
  v_seq      fgc.contract_status_event.event_seq%TYPE;
  v_executor bigint;
BEGIN
  SELECT j.state, j.attempt_count, j.error_code, e.contract_id, e.event_seq
    INTO STRICT v_state, v_attempts, v_error, v_contract, v_seq
    FROM fgc.contract_event_job j
    JOIN fgc.contract_status_event e USING (contract_status_event_id)
   WHERE j.contract_status_event_id = v_event AND j.processing_job = v_job
     FOR UPDATE OF j;
  IF v_state = 'SUCCEEDED' OR v_attempts < c_max THEN
    RAISE EXCEPTION '소진된 작업이 아니다 (상태 %, 시도 %)', v_state, v_attempts;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
                  WHERE u.login_id = current_setting('recovery.approver') AND r.role_code = 'GA_ADMIN') THEN
    RAISE EXCEPTION '승인자는 GA_ADMIN 역할 계정이어야 한다';
  END IF;
  SELECT user_id INTO v_executor FROM fgc.app_user WHERE login_id = current_setting('recovery.executor');
  IF v_executor IS NULL OR current_setting('recovery.approver') = current_setting('recovery.executor') THEN
    RAISE EXCEPTION '실행자는 승인자와 다른 등록 계정이어야 한다';
  END IF;
  -- claimNext는 같은 계약의 앞선 사건이 끝나기를 기다린다. 앞선 작업부터 푼다.
  IF EXISTS (SELECT 1 FROM fgc.contract_event_job p
               JOIN fgc.contract_status_event pe USING (contract_status_event_id)
              WHERE pe.contract_id = v_contract AND pe.event_seq < v_seq AND p.state <> 'SUCCEEDED') THEN
    RAISE EXCEPTION '같은 계약의 앞선 사건 작업부터 처리한다';
  END IF;
  -- 시도 횟수를 한도 바로 아래로만 내려 자동 재시도를 정확히 1번 더 허용한다.
  UPDATE fgc.contract_event_job
     SET attempt_count = c_max - 1, next_retry_at = clock_timestamp()
   WHERE contract_status_event_id = v_event AND processing_job = v_job;
  INSERT INTO fgc.audit_log (user_id, action_code, entity_type, entity_id, before_value, after_value, reason, request_id)
  VALUES (v_executor, 'CONTRACT_EVENT_JOB_MANUAL_RETRY', 'CONTRACT_EVENT_JOB', v_event || ':' || v_job,
          jsonb_build_object('state', v_state, 'attempt_count', v_attempts, 'error_code', v_error),
          jsonb_build_object('attempt_count', c_max - 1, 'contract_id', v_contract,
                             'approved_by', current_setting('recovery.approver'),
                             'executed_by', current_setting('recovery.executor')),
          current_setting('recovery.reason'), current_setting('recovery.ticket'));
END
$recovery$;
COMMIT;
SQL
```

**검증 [T15]:**

| 결과 | 뜻 | 다음 |
|---|---|---|
| `state=SUCCEEDED`, 처리 이력에 `SUCCEEDED` 1행 | 복구됨 | 같은 계약의 뒤 사건이 이어서 처리되는지 본다 |
| `state=FAILED`, `attempt_count=5`, 처리 이력에 `FAILED` 추가 | 다시 소진 | 미해결로 남긴다. 원인을 다시 본다 |
| 앞선 사건이 있다는 오류 | 순서 위반 | 앞선 작업부터 한다 |

T15는 처리기가 없던 이미지에서 시험 처리기로 돌린 기록이다. `PaymentHoldJob`, `PaymentResumeJob`의 소진과 재시도는 코드 대조만 했고 리허설 때 확인한다. 성공한 뒤에는 후속 상태의 작업별 요약 건수를 확인한다. 대상별 처리·제외 근거는 IF-API-89로 조회한다. `PaymentHoldJob`이 성공해 보류가 걸린 지급 건은 계속 확정할 수 없다. 이것은 정상이다.

---

## 8. 파일 볼륨 가득 참

환수 PDF 원본은 `FGC_STORAGE_ROOT`(`/var/lib/fgc/files`)에 있다. PR #812가 병합돼 Pod는 named volume `fgc-files`, 신한은 `fgc-shinhan_files`를 쓴다. 파일당 상한은 `FGC_UPLOAD_MAX_BYTES`(기본 20MB)다.

**볼륨에 용량 제한이 없다.** named volume은 서버 디스크를 그대로 쓰므로 "볼륨 가득 참"은 **서버 디스크 가득 참**이다. DB, 로그, 이미지도 같은 디스크다.

| 단계 | 내용 |
|---|---|
| 탐지 | 환수 `[PDF 생성]`이 `처리 중 오류가 발생했습니다.` + 요청번호. 로그에 `환수 통지서 PDF를 저장할 수 없습니다.`와 `No space left on device`. **health는 `UP`이고 다른 화면은 정상이다** [T21]. `df -h` |
| 초기 판단 | 무엇이 차지하는지 본다. 아래 명령 |
| 담당 | DevOps 실행, PM이 지울 대상 승인 |
| 조치 | 아래 순서 |
| 검증 | `df -h`, 같은 화면에서 다시 `[PDF 생성]`, `[다운로드]` |
| 사후 기록 | 1-4절, 지운 것과 확보한 크기 |

```bash
df -h; docker system df
du -sh "$state/backups" "$state/releases"
docker volume inspect fgc-shinhan_files --format '{{.Mountpoint}}'
docker run --rm -v fgc-shinhan_files:/data:ro alpine du -sh /data
```

**비우는 순서:**

1. 안 쓰는 이미지: `docker image prune -a --filter until=168h`. 현재 이미지는 지워지지 않는다. 롤백할 이전 digest는 다시 받아야 하므로 GHCR이 정상일 때만 한다.
2. 지난 백업: `$state/backups/*.dump`를 서버 밖으로 옮기고 지운다. `deploy-shinhan.sh`는 배포마다 dump를 만들고 지우지 않는다. 가장 최근 것과 `db-backup-path.txt`가 가리키는 것은 남긴다.
3. 지난 릴리스 폴더 `$state/releases/`. 최근 것은 롤백에 쓰므로 남긴다.
4. 컨테이너 로그는 서비스당 10MB 3개로 이미 제한돼 있다.

**하지 않을 일:** `fgc-shinhan_files` 안의 파일을 지우지 않는다. 지운 판을 받으면 `FGC-COMMON-500`이 난다 [T21]. 판 번호와 저장키는 `generated_document`에 있고 원본은 복구할 수 없다.

**복구 동작 [T21]:** 저장이 실패하면 파일 조각, 문서 행, 멱등 요청이 남지 않는다. 공간을 확보한 뒤 같은 요청을 다시 보내면 새 판이 정상 발행된다.

**볼륨 전에 발행한 판:** PR #812 전에 발행한 PDF는 재배포 때 사라졌다. 새 판으로 다시 발행한다(트래커 4번).

DB까지 쓰기 실패하는 단계는 격리 환경에서 시험하지 않았다. 미확인. DB 로그에 `No space left`가 보이면 위 순서로 공간을 만든 뒤 API를 재기동하고 2-4절로 확인한다. 데이터가 이상하면 0장 DB 손상으로 간다.

---

## 9. 시연 DB와 파일 복원

매일 02:00 일 배치가 변경분을 처리하고(변경이 없으면 실행 행을 만들지 않는다) 승인, 확정은 되돌릴 수 없으므로 리허설(11/10, 11/12, 11/13)마다 **DB와 파일을 같은 스냅샷으로** 복원한다(트래커 4번, PMR-007, TER-010) [SRC-067]. `deploy-shinhan.sh`의 백업은 DB만 받으므로 이 절차가 따로 필요하다.

| 단계 | 내용 |
|---|---|
| 담당 | 실행 DevOps, 복원 결정 PM, 값 확인 시연 담당 |
| 멈추는 것 | **API를 멈추면 일 배치와 후속 작업 스케줄러가 함께 멈춘다** (`@Scheduled`). 끝나면 다시 켠다 |
| 훈련 | 격리 환경 스냅샷, 변경, 복원 완주 [T20]. 복원과 기동 66초(작은 시드 DB 값) |

### 9-1. 스냅샷

```bash
snap=~/fgc-stg/snapshots/rc-$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$snap"
psql_fgc -At -c "SELECT count(*) FROM fgc.validation_run WHERE status IN ('CREATED', 'RUNNING');" </dev/null   # 0 이어야 한다
psql_fgc -At -c "SELECT count(*) FROM fgc.validation_run_retry WHERE state IN ('ACCEPTED', 'LAUNCHING', 'RUNNING');" </dev/null   # 0 이어야 한다 (#844 뒤 추가, 코드 대조)
"${compose[@]}" stop web api
"${compose[@]}" exec -T db pg_dump -U fgc -d fgc -Fc > "$snap/db.dump"
docker run --rm -v fgc-shinhan_files:/data:ro -v "$snap:/snap" alpine tar -C /data -czf /snap/files.tgz .
(cd "$snap" && sha256sum db.dump files.tgz > SHA256SUMS)
```

메타데이터를 `$snap/snapshot.json`에 남긴다. 값은 `current.json`과 아래 SQL에서 옮긴다.

```json
{
  "rc_commit": "<40자리 커밋>",
  "api_image": "ghcr.io/feegachu/fgc/api@sha256:<digest>",
  "web_image": "ghcr.io/feegachu/fgc/web@sha256:<digest>",
  "flyway_last_applied": "<installed_rank 최대 행의 버전>",
  "flyway_max_version": "<최대 버전>",
  "dataset": "<데이터셋 이름과 사전 등록 예외 목록>",
  "as_of": "<시연 기준일>",
  "taken_at": "<시각>",
  "active_runs_at_snapshot": 0,
  "generated_documents": 0,
  "validation_runs": 0,
  "ai_usd_in_log": "<5-5절 값>"
}
```

```sql
SELECT version FROM fgc.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;
SELECT max(version::numeric) FROM fgc.flyway_schema_history WHERE success AND version ~ '^[0-9]+$';
SELECT count(*) FROM fgc.generated_document;
SELECT count(*) FROM fgc.validation_run;
```

순서 밖 적용 뒤에는 첫 값과 둘째 값이 다르다(예: 73, 77) [T20]. 스냅샷을 찍은 뒤 `"${compose[@]}" up -d --no-deps api`, 2-6절처럼 health를 기다려 `"${compose[@]}" up -d --no-deps web`로 다시 켠다. 스냅샷은 저장소에 넣지 않는다.

### 9-2. 복원

먼저 5-5절 SQL을 돌려 그 값을 9-3절 표에 적는다. 복원하면 `ai_call_log`도 돌아가기 때문이다.

```bash
snap=~/fgc-stg/snapshots/<스냅샷>
(cd "$snap" && sha256sum -c SHA256SUMS)
"${compose[@]}" stop web api
"${compose[@]}" exec -T db pg_restore -U fgc -d fgc --clean --if-exists --single-transaction < "$snap/db.dump"
docker run --rm -v fgc-shinhan_files:/data -v "$snap:/snap:ro" alpine sh -c 'find /data -mindepth 1 -delete && tar -C /data -xzf /snap/files.tgz'
"${compose[@]}" up -d --no-deps api
until curl -fs http://127.0.0.1:18081/actuator/health | grep -q '"UP"'; do sleep 2; done
"${compose[@]}" up -d --no-deps web
```

앱 이미지가 스냅샷과 다르면 복원 전에 그 RC로 맞춘다(2장).

**검증 [T20]:**

| 항목 | 기대 |
|---|---|
| Flyway, 건수 | `snapshot.json`과 같다 |
| 파일과 DB 일치 | 아래 두 목록이 같다 |
| 스냅샷 전 PDF | 다운로드 200, 원본과 같은 바이트 |
| 스냅샷 뒤에 만든 판 | 404 |
| 활성 실행 | 0 |
| 시연 값 | 콘티의 기대 값. `LocalDate.now()`에 기대는 값은 날짜가 다르면 바뀐다(트래커 0번, 7번) |

```bash
psql_fgc -At -c 'SELECT storage_key FROM fgc.generated_document ORDER BY 1;' </dev/null | tr -d '\r' > /tmp/keys.txt
docker run --rm -v fgc-shinhan_files:/data:ro alpine ls /data | tr -d '\r' | sort > /tmp/files.txt
diff /tmp/keys.txt /tmp/files.txt && echo 일치; rm -f /tmp/keys.txt /tmp/files.txt
```

`<` 줄은 DB에 있는데 파일이 없는 판이고 다운로드하면 500이 난다(8장). `>` 줄은 DB가 모르는 파일이고 해롭지 않다.

### 9-3. 리허설 기록

| 회차 | 날짜 | 스냅샷 | 복원 시간 | 복원 전 AI 사용액 | 검증 | 담당 |
|---|---|---|---|---|---|---|
| 1 | 11/10 | | | | 미수행 | |
| 2 | 11/12 | | | | 미수행 | |
| 3 | 11/13 | | | | 미수행 | |

AWS Pod의 파일 볼륨은 이름이 `fgc-files`이고 Podman 명령을 쓴다. 신한이 발표 서버라 AWS 복원은 시험하지 않았다. 미확인.

### 9-4. 시연 데이터 쌓기 (11월 기준)

기본 정산월은 `FGC_DEMO_MONTH=2026-11`이다(compose, Pod). 시드를 고치지 않고 `scripts/demo-data/load_demo_months.py`가 화면과 같은 업무 API로 계약 등록, 지급 확정, 상태 변경, 월 통합검증, 7~9월 확정을 차례로 돌린다. 판정과 원장, 예외는 전부 서버가 계산한다. 약 3분 걸리고 다시 돌려도 이미 있는 계약과 지급은 건너뛴다. 금액과 사람은 가상이고 업무 규칙이 아니다.

1. RC 배포를 끝낸다(V83이 들어가야 한다). 9-1절로 스냅샷을 받는다. 확정은 되돌릴 수 없다.
2. 정산담당자 접속을 닫고 로컬에서 실행한다. 같은 계정의 다른 접속은 끊긴다.

   ```bash
   python scripts/demo-data/load_demo_months.py --base https://<서버> --accounts <계정 파일> --env shinhan
   ```
   앞단을 거치면 요청마다 왕복이 붙어 복제 DB의 94초보다 오래 걸린다(10~30분 추정, 실측 전). 스크립트는 파이썬 기본 User-Agent가 앞단에서 403(1010)으로 막혀 도구 이름을 보낸다.
3. 결과 줄의 실행 번호를 확인하고, 다음 날 일 배치 뒤 화면을 한 번 본다. 일 배치가 그날 바뀐 계약을 다시 볼 수 있다.
4. 이 상태로 9-1절 스냅샷을 다시 받는다. 리허설과 발표 전 복원 지점이 된다.

| 달 | 상태 | 시연에서 보여 줄 것 |
|---|---|---|
| 7, 8월 | 확정(잠김) | 시드의 오류 시나리오와 처리 이력 |
| 9월 | 확정(잠김) | 신계약 5건, 한도 초과로 지급을 막은 회차, 마감 체크리스트 |
| 10월 | 계산완료, 대사 불일치 처리 중 | 금액 차이 `FGC-FGL01-202610-0109`, 지급 누락 `FGC-FGN01-202610-0108`, 청약철회 뒤 지급 `FGC-FGL04-202610-0107`, 공통비 안분 |
| 11월 | 진행 중 | 중복 지급 `FGC-FGN01-202611-0113`, 작성중 지급 `FGC-FGL01-202611-0112`, 한도 초과 `FGC-FGL02-202611-0114`, 정착지원금 한도 초과 `FGC-FGN01-202611-0113`, 미납 보류 `FGC-FGL01-202609-0101`, 실효 `FGC-FGL04-202609-0104`, 부활 `FGC-FGL01-202609-0103`, 해지와 환수 `FGC-FGN01-202609-0102`, 청약 `FGC-FGL01-202611-0116` |

11월 지급은 발표일(11/18)까지 도래한 회차만 처리한다. 그 뒤 회차는 지급예정일이 오기 전까지 누락으로 잡히지 않는다.

---

## 부록 A. 모의훈련 기록

- **일시:** 2026-11-04, KST
- **기준:** 훈련 이미지 develop `185dc854dc2f037a93a1fe2ad7717a8929a3196e`(Flyway V77). 이전 이미지 `991d8c44`(V76). #836은 `8f685374`. 화면 대조와 코드 대조는 문서 확정 시점의 `274477d6`
- **훈련 뒤 병합:** #774(V78, 데이터 변경 없는 컬럼 추가)와 #833(`LlmGateway` 호출 한도, 지시문 검사, 시간 제한). 이 둘은 T1~T24의 이미지에 없다. 그래서 head 이미지로 T25, T26, T27을 따로 돌렸다. 그 뒤 병합된 #773(RPT-W02), #831(V79, `PaymentHoldJob`), #835(`PaymentResumeJob`, 영향 미리보기), #859(상태 이력)는 어느 훈련 이미지에도 없다. 해당 절은 코드 대조이고 리허설에서 확인한다(부록 C). #862, #871, #870, #844(V80, `/restart`)는 T28, T29, T30, T31로 따로 확인했다.
- **격리:** 로컬 Docker. 프로젝트 이름 `fgc676drill`, 포트 18676, 18677. 저장소 공개 이미지를 digest로 받아 `infra/shinhan/compose.yaml`로 띄웠다(`SPRING_PROFILES_ACTIVE=prod`, PostgreSQL 17). 덮어쓴 값은 포트, 메모리, LLM 주소뿐이다. 개발계, 운영, 신한 서버에 접속하지 않았다
- **이미지:** api `sha256:1553efdf…`, web `sha256:58978f38…` / 이전 api `sha256:da9d21e3…`, web `sha256:ede81bf2…`
- **장애 주입:** 격리 DB에서만 했다. 룰셋 시작일을 임시로 미래로 옮김(`trg_cap_rule_set_policy_lock`을 그 트랜잭션에서 끄고 복구), `journal_header` 삽입 차단 트리거, `docker kill`, 429를 돌려주는 nginx 모의 서버, 1MB tmpfs
- **비밀값:** 일회용 값을 썼고 환경과 함께 지웠다. T19의 첫 시도는 예외다

| # | 훈련 | 실행한 것 | 결과 요지 | 판정 |
|---|---|---|---|---|
| T1 | 배포 재현 | 빈 DB에 V76 이미지 기동 → `pg_dump` → V77 이미지 승격 → `nginx -t` → 웹 교체 → 로그인 경로 | `UP` 27초, 24초. `/login` 200, 로그인 경로 401, 공개 `/actuator/health` 404 | 통과 |
| T2 | 복원 없이 V76으로 되돌림 | V77 DB에 V76 이미지 | **앱이 뜬다.** `Schema "fgc" has a version (77) that is newer...` 경고. 건강해 보여도 V77 데이터 변경은 남음 | 위험 확인 |
| T3 | 복원 먼저 | `stop` → 추가 백업 → `pg_restore --clean --if-exists --single-transaction` → V76 기동 | exit 0, 최신 76, 61초 | 통과 |
| T4 | #836 검사 SQL | 시드 DB에 `unprotected-sql`, agent01 검사, pending | `admin,audit01,gaadmin,settle01`, `t`, `3` | 차단 확인 |
| T5 | 계정 보호 | 4-2절 SQL 5계정(`CHANGE` 4, `LOCK` 1) → 재검사 → 옛 비밀번호 → 새 비밀번호 → 옛 토큰 → 로그 | 재검사 빈 값. 5계정 모두 행 있고 옛 비밀번호 401. 새 비밀번호 200(`$2y$`), 잠근 계정 401. 보호 전 토큰 401. 앱 로그에 평문 0건. 감사에 해시 0건 | 통과 |
| T6 | 해시 검사 한계 | 공개 비밀번호를 새 salt로 다시 해시 | **검사는 빈 값, 로그인은 200.** 4-3절이 필요한 이유 | 한계 확인 |
| T7 | agent01, 순서 밖 적용 | 보호 뒤 #836 compose + `true` → V66, V72, V73 적용 → `false` 재기동 → 복원 없이 이전 compose → 복원 먼저 | 세 버전 성공, agent01 비밀번호 유지(옛 401, 새 200), 재기동 정상. 복원 없이는 `Detected applied migration not resolved locally: 66, 72, 73`, 컨테이너 `Up`, health 404. 복원 먼저면 정상, 148초 | 통과 |
| T8 | 시나리오 1 재현 (BE-03) | 상태 사건 없는 계약을 `updated_at`만 갱신 → 룰셋 누락 → 일 배치 → 룰셋 복원 → 일 배치 | 실행 `COMPLETED`, 예외 `CONTRACT_REVALIDATION_FAILED`, 워터마크 전진. 다음 실행 읽은 수 **0**, 예외 그대로 | 재현 |
| T9 | CAP 수동 재검증 | `POST /contracts/{id}/cap-check` | `cap_check` 2행(`MANUAL`, 실행 번호 없음). 예외 `NEW` 유지, 차익거래 행 없음, 워터마크 불변 | 확인 |
| T10 | 가드와 동시 실행 | 승인자 역할 틀림, 계약 불일치, 실패 실행 불일치, 활성 실행 있음. 활성 실행 중 새 실행 | SQL 4종 모두 거절, 변경 0, 감사 0. 새 실행 `FGC-VRUN-001` | 통과 |
| T11 | 원인 미수정 복구 | 룰셋 누락 상태로 후퇴 → 실행 | 재선별 1건, 다시 실패. 예외 `NEW`, `detection_count` 2, 새 실행 연결 | 검토 큐 유지 |
| T12 | 승인 복구 완주 | 룰셋 복원 → 후퇴 → 실행 → 3-6절 → 예외 종결 | 감사에 승인자, 실행자, 전후 워터마크, 예외, 계약, 실행. 읽은 수 1, CAP 정상 2행, 차익거래 1행, 재검출 0, 새 스케줄 헤더 없음. 예외 `RESOLVED`, 증빙 `validation_run:6,audit_log:49,ticket:...` | 통과 |
| T13 | 월 실행 실패 재시작 | `journal_header` 삽입 차단 → 실행 → 원인 그대로 재시작 → 원인 제거 재시작 | `FAILED` 단계 6 → 재시작도 실패 → `COMPLETED`. JobInstance 7 하나에 실행 3개, 마지막 실행은 `journalPostingStep`부터. 감사 `VALIDATION_RUN_RETRIED` 2 | 통과 |
| T14 | RUNNING 고착 | 실행 중 `docker kill` → 재기동 → 6-3절 SQL → 재시작. 계약수동 고착도 | `RUNNING`, `STARTED` 남음. 실행 `FGC-VRUN-004`, 새 실행 `FGC-VRUN-001`. SQL 뒤 `FAILED`, 재시작 `COMPLETED`. 계약수동은 새 실행 성공 | 통과 |
| T15 | 후속 작업 소진 | 시험 행을 실제 앱 스케줄러에 태움: 5번째 실패 → 15초 대기 → 승인자 틀림 → 7장 SQL → 재시도. 성공 경로는 PostgreSQL 17 Testcontainers와 시험 처리기로 같은 SQL 실행(`ContractStatusEventIntegrationTest`에 임시 시험 추가, 커밋 안 함, 클래스 16개 통과) | 자동으로 안 잡힘. 승인자 틀림 거절. 수동 뒤 1번만 다시 시도하고 `FAILED`, 5, 실패 이력 추가, 감사 기록. 성공 경로: 뒤 사건까지 순서대로 `SUCCEEDED`, 성공 이력 1, 영향 1, 감사 1. 뒤 사건을 먼저 풀면 거절. 다시 실패하면 1번만 시도하고 재소진 | 통과 |
| T16 | 쿼터 소진 | 429 모의 서버에 키 있는 API, 피코 2회, 상품 추천 1회 | 첫 호출 `HTTP_429_insufficient_quota`, 이후 `AI_DISABLED`. 모의 서버 호출 1회. 피코 `FIXED`, 상품 추천 `RULE`, 순위 `READY` | 통과 |
| T17 | 예산 SQL | 실제 스키마에 호출 1,000건 삽입 후 5-5절 | 1.2000달러, 잔액 8.8000. SRC-060 계산과 일치. 삽입 행 삭제 | 통과 |
| T18 | 규칙형 전환 | 키 비우고 재기동, 질문 | 기동 로그 1건, `FALLBACK_RULE`, `NO_API_KEY`, 모의 서버 호출 없음 | 통과 |
| T19 | 키 교체 | 일회용 값으로 AI 키, JWT, DB 비밀번호 교체 → `up -d db api` | 지문 일치. 옛 access token 401, refresh 200, 재기동 49초. DB 컨테이너 다시 만들어져도 데이터와 연결 유지. **첫 시도에서 작업 셸의 같은 이름 변수가 `.env`를 덮어 일회용이 아닌 값이 격리 컨테이너에 들어갔다. 컨테이너를 다시 만들고 변수를 지운 뒤 다시 돌렸다. 5-1절을 넣은 이유다** | 통과 |
| T20 | 스냅샷과 복원 | 스냅샷 → 실행과 PDF 재발행 → 복원 → 대조 | 저장키와 파일 일치, v1 200 같은 바이트, v2 404, 실행과 문서 건수 복귀, 복원과 기동 66초 | 통과 |
| T21 | 볼륨 가득 참 | 1MB tmpfs를 채우고 PDF 발행 → 공간 확보 → 같은 멱등키 재시도. 빈 볼륨에서 옛 판 다운로드 | 500 + 요청번호, `No space left on device`. health 200, 계약 목록 200. 문서 행, 멱등 행, 조각 없음. 재시도 201, 다운로드 200. 원본 없는 판은 500 | 통과 |
| T22 | 배포 스크립트 시험 | `python -m unittest discover -s infra/scripts/tests` | develop 17개, #836 22개 통과 | 통과 |
| T23 | 문서 전용 커밋 승격 | `verify_image`에 commit과 라벨이 다른 값 | `api: pulled image revision differs from staging` 거절 | 결함 확인 (2-1절) |
| T24 | 없는 digest | `docker pull .../api@sha256:000…` | 해석 실패. 스크립트는 `set -e`로 변경 전에 멈춘다(`bad_digest` 시험) | 통과 |
| T25 | 화면 문구 | 매뉴얼의 버튼, 문구를 소스와 대조(주석 제외), head 이미지의 실제 화면을 headless Chromium으로 순회(로그인, 메뉴, 계약, 지급, 월 통합검증 생성과 실행, 대사, 예외 처리, 환수, 정책, 감사, 설계사) | 버튼 60개, 문구 136개 대조 불일치 0(표기 설명 1개, 조합 문구 1개 제외). 화면 106개 확인 통과. 순회에서 매뉴얼 오류 5건을 찾아 고쳤다: `[후보 조회]`가 실제로 `[조회]`, 보험회사는 검색이 아닌 목록, 대사 실행은 지급단계와 보험회사를 정해야 켜짐, 정책은 행의 `선택` 뒤 `[선택 정책 관리]`, 실행 생성 뒤 상세로 자동 이동 | 통과 |
| T26 | head 이미지 승격, 호출 한도 | `274477d6` 이미지로 올림(V78 적용) → 500 모의 서버로 피코 15회 | V78 성공, `UP`. 호출 12회 뒤 `FALLBACK_RULE`, `RATE_LIMITED` 3건, 모의 서버 호출 12회 | 통과 |
| T27 | 설계사 범위 | `agent01`로 계약 목록과 남의 계약 조회, 감사 옵션 조회 | 본인 13건. 남의 계약 403 `FGC-AUTH-005`. 감사 `'행위 종류'` 목록에 수동 절차 행위 코드 4개가 나온다 | 통과 |
| T28 | 행 존재 검사 | PR #862 `55469651`에서 `PYTHONUTF8=1 python -m unittest discover -s infra/scripts/tests`. 임시 PostgreSQL 17에서 `missing-accounts-sql` 실행 | 시험 26개 통과. `agent01` 없음 → `agent01`, 모두 있음 → 빈 값, 두 개 없음 → `admin,settle01` | 통과 |
| T29 | `CREATED` 잔존 | `ArbitrageTransactionBoundaryIntegrationTest.rowLeftBeforeThisFixIsClearedByExecuteSoTheNextCheckIsNotBlocked` (PR #871, Testcontainers PostgreSQL 17). 3일 전 `CREATED` 행 → 수동 검증 → `FAILED`로 UPDATE → `execute` → 수동 검증 | `FGC-VRUN-001`. 트리거 거절. 실행 `COMPLETED`. 수동 검증 성공. 화면 버튼과 일 배치 경로는 코드 대조만 | 통과 |
| T30 | 로그인 시도 제한 | `nginx:stable-alpine`에 `25626562`의 실제 템플릿(`adapt_nginx` 적용), `nginx-guard.conf`, `nginx-http.conf`, 401을 돌려주는 모의 API. 앞단은 없고 헤더를 직접 넣음 | 같은 마지막 값 15회(앞 값은 매번 다름): 401 11건, 429 4건. 요청마다 다른 값 15회: 401 15건. 헤더 없음 15회: 401 15건. 막힌 동안 다른 값: 401. 막힌 뒤 첫 통과 1.2초. 소진 뒤 2, 6, 10, 22초 쉬고 연속 14회: 통과 2, 4, 6, 11건. 같은 값 20건 병렬: 401 11건, 429 9건. web 로그 마지막 칸 `"-"`, `"앞 값, 마지막 값"`. 2-4절 측정 명령 실행 | 통과. 실제 앞단은 미확인 |
| T31 | 재시작 고착 복구 | `sha-2c460fd3` api 이미지(V80, 재시작 코드는 `5f21741a`와 같음), 빈 PostgreSQL 17, 시드. 6-3절의 두 SQL을 문서에서 뽑아 그대로 `psql`로 실행. 격리 DB에 `pg_sleep` 트리거를 걸어 멈춘 뒤 `docker kill`로 다섯 상태를 만듦: `LAUNCHING`과 새 배치 실행 없음, `LAUNCHING`과 배치 `STARTED`, `RUNNING`과 실행 `FAILED`, `RUNNING`과 실행 `RUNNING`, `RUNNING`과 실행 `COMPLETED` | 다섯 상태 모두 재기동 뒤에도 접수 행이 남음. 닫기 전: 그 실행과 같은 달 다른 실행의 재시작 불가, 새 월간 실행 409 `FGC-VRUN-001`, 확정 409 `FGC-VRUN-005`. 닫은 뒤: 재시작 허용, 새 월간 실행 201, 확정은 422 `FGC-VRUN-002`(확정 조건 판단으로 넘어감). `RUNNING` 실행은 멈춘 실행 SQL을 먼저 돌려야 하고, 그 SQL만으로는 막힌 그대로임을 확인. 거절: `SUCCEEDED`, `FAILED`, `ACCEPTED`, 없는 접수, 승인자 역할 틀림, 승인자와 실행자 같음, 없는 실행자. 모두 변경 0, 감사 0. 트리거 오류 없음. 복구를 거친 재시작은 같은 JobInstance의 실행 2, 5, 6, 7, 8로 이어져 실행 8이 `journalPostingStep`부터 `COMPLETED`. 감사 5건. 확인 37개 | 통과. 실제 서버는 미확인 |

**돌리지 못한 것**

| 항목 | 사유 | 언제 |
|---|---|---|
| 실제 서버의 `deploy-shinhan.sh`, `deploy-host.sh`, Jenkins 파이프라인 전체 | 실 서버 접속 금지. 스크립트는 스텁 시험, 명령은 격리 compose로만 | 리허설 11/10 |
| 실제 서버 롤백, 실제 데이터 크기의 복원 시간 (RPO, RTO는 #675) | 같음 | 리허설 11/10 |
| 신한 서버의 계정 보호와 로그인 거절 | 같음. 4장 명령은 격리 DB에서 확인 | 공개 전, #836 배포 전 |
| 실제 OpenAI 키 교체, 실제 쿼터 소진 | 실제 키를 쓰지 않음. 429는 모의 서버 | 리허설 |
| AWS Pod의 Secret 교체, volume 복원 | Podman 환경 없음 | 필요할 때 |
| Jenkins, GHCR 실제 장애 | 외부 서비스 | 탁상 점검 |
| 서버 디스크가 실제로 차서 DB가 쓰기 실패하는 단계 | 격리 환경에서 재현하지 않음 | 미정 |
| 사람이 이 문서만 보고 수행 | 팀 일정 | 리허설 11/10 |

## 부록 B. 이슈 #676 시나리오 대응

| # | 시나리오 | 절 | 훈련 |
|---|---|---|---|
| 1 | 미처리 사건 없는 `updated_at` 계약 실패, 워터마크 전진 | 3장 | T8~T12 |
| 2 | 데모 계정 보호 검증 누락 또는 실패 | 4장 | T4~T7 |
| 3 | 배포 재현 | 2-1~2-4절 | T1, T22. 실 서버 미확인 |
| 4 | 롤백 | 2-5, 2-6절 | T2, T3, T7. 실 서버 미확인 |
| 5 | 키 교체 | 5-1~5-3절 | T19. 실제 키 미확인 |
| 6 | 배치 실패 대응, 재시작 | 6장 | T13, T14, T31 |
| 7 | 쿼터 소진, 규칙형 전환 | 5-4절 | T16, T18 |
| 8 | 업로드 볼륨 가득 | 8장 | T21 |

## 부록 C. 리허설(11/10)에서 확인할 것

1. 신한 서버 실제 배포 1회, 2-4절 검증, RC가 코드 커밋인지(2-1절).
2. 2-4절 앞단 경계 측정(#870, 미측정). 결과를 보안시험 결과서에 적는다.
3. 공개 전 4-1~4-3절, 이어서 4-5절.
4. #871을 배포한 뒤 6-5절 SQL로 남은 `CREATED` 행을 본다.
5. 9장 스냅샷, 복원. 시간을 9-3절에 적는다.
6. 실제 키로 지문 확인과 질문 1건, 사용액 기록.
7. 담당과 연락망 확정(0장).
8. 팀원 1명이 매뉴얼 8장을 수행.
9. RC 이미지로 1회: 6-2절 재시작과 6-3절 재시작 도중 중단(격리 환경은 T31), 7장 실제 처리기 소진과 재시도(코드 대조만).
10. 발표 회선에서 신한 주소의 `/cdn-cgi/trace`로 Cloudflare 거점(`colo`)을 보고, 빈 브라우저로 첫 화면 시간을 잰다. 2026-11-06 노트북 회선 측정: 이 주소는 `LAX`, 같은 회선의 cloudflare.com은 `ICN`이다. 로그인 화면 3.1~6.4초, 로그인 뒤 홈 약 3.5초, API 1건 0.4~1.1초. 앞단 없이 같은 구성을 재면 로그인 화면 0.8초, 홈 0.6초라 차이는 경로 비용이다. 코드로 줄일 수 있는 폭이 작으니 시연 PC는 시작 전에 한 번 열어 둔다(정적 파일은 그 뒤 브라우저 캐시). 성능시험 결과서의 값은 앞단을 포함하지 않는다.
11. (#912 병합, 배포 뒤) 발표 PC에서 브라우저를 "시작할 때 이전 탭 계속" 설정으로 둔 채 로그인하고 브라우저를 닫은 뒤, 2분 뒤와 4분 뒤에 다시 연다. 2분 뒤는 로그인이 이어지고 4분 뒤는 자동 로그아웃 안내가 나와야 한다(#912, 화면을 모두 닫고 3분). 부스 노트북은 사람이 바뀔 때 로그아웃 버튼을 누른다.

## 개정 이력

| 판 | 날짜 | 내용 | 근거 |
|---|---|---|---|
| v1.0 | 2026-11-04 | 최초 작성, #676 | FGC-PSR-005, SRC-060, SRC-065, SRC-067, SRC-075, SRC-076, SRC-077, 운영정책서 '일일 변경분 처리' |
| v1.0 | 2026-11-04 | 6-5절 추가: 수동 차익 검증이 남긴 `CREATED` 행, #871 | 코드 대조, T29, V13, V7 `guard_run_lifecycle` |
| v1.0 | 2026-11-04 | 병합분 반영: 머리 표(`5f21741a`, V80), 0장 증상 표, 1-6절 로그인 시도 제한, 2-2절 스크립트 동작, 2-4절 앞단 경계 측정, 4-4절, 6-1~6-4절 재시작, 6-3절 재시작 고착 복구 절차(PM 결정, 팀 공유 전), 7장 처리기와 소진 조회식(처리 중 제외, 임시 표 5행으로 확인), 9-1절, 부록 A, C. T30, T31 추가 | #831, #835, #844, #859, #862, #870 코드 대조, PR #870 6절, T30, 인터페이스정의서 §4-4-2, §4-4-4, SRC-028, SRC-065 |
| v1.0 | 2026-11-06 | 부록 C 10번 추가: 신한 앞단 경로 측정 | 실측(curl, 브라우저), PR #918 |
| v1.0 | 2026-11-07 | 부록 C 11번 추가: 탭 복원 설정의 자동 로그아웃 확인(#912 배포 뒤) | PR #912 리뷰, PR #918 |
