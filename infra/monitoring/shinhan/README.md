# 신한 prod 모니터링 배포

모니터링은 기존 API/WEB/DB와 별도 Compose 프로젝트로 관리한다. Prometheus와 Blackbox만 기존 API의 Docker 내부망에 참가하여 `api:8080`을 수집한다. API의 loopback 포트를 Linux host-gateway로 우회하지 않는다. 공개 Nginx의 `/actuator` 차단은 유지한다.

이 파일은 배포 구성이다. 파일 추가나 PR 생성은 서버 배포 완료를 뜻하지 않는다.

## 사전 확인

- 실제 SSH 대상과 운영 배포 계정을 확인한다. AWS 별칭을 신한 대상으로 간주하지 않는다.
- 운영 API의 이미지 digest/소스 버전과 #674 포함 여부를 확인한다. 모니터링만 추가하려고 운영 앱을 과거 feature 브랜치 이미지로 덮어쓰지 않는다. 계측 코드가 없는 이미지는 검증된 승격 경로로 먼저 반영한다.
- 기존 `~/fgc-stg`의 compose 프로젝트명, API 컨테이너, 현재 release.env와 네트워크를 확인한다. 서버 이름에 stg가 있어도 현재 운영일 수 있다.
- `docker inspect <API컨테이너> --format '{{json .NetworkSettings.Networks}}'`로 실제 네트워크를 선택하고 서비스 별칭 `api`가 있는지 확인한다. 후보가 여러 개면 외부 연결용 네트워크를 임의로 선택하지 않는다.
- CPU/메모리/디스크 여유, UI 포트 충돌을 확인한다. 설정 기본 메모리 상한 합계는 960 MiB이며 서버에 맞게 조정한다. Prometheus 데이터 보존 기본값은 15일이다.
- 운영 Discord 채널과 Webhook URL을 확정한다. smoke fixture나 개발 PC를 운영 수신기로 지정하지 않는다. 외부 수신 채널 미연결 상태를 알림 구축 완료로 보고하지 않는다.

## 준비

검증된 구성 파일을 서버의 별도 디렉터리(예: `~/fgc-monitoring`)에 복사한다. 기존 `~/fgc-stg`와 데이터 볼륨을 덮어쓰지 않는다. 아래 모니터링 명령의 작업 디렉터리는 `~/fgc-monitoring`이며, 저장소 `infra/monitoring/`의 내용이 이 디렉터리 안에 위치한다.

서버에서 `secrets/`를 만들고 다음 파일을 준비한다.

- `secrets/scrape-token`: 서버에서 생성한 임의 토큰. 앱과 Prometheus에 같은 값 사용.
- `secrets/grafana-password`: 운영 Grafana 관리자 비밀번호.
- `secrets/discord-webhook-url`: 운영 Discord 채널의 Webhook URL. 신한 override는 `discord_configs`를 사용하며, generic webhook 형식을 Discord URL로 보내지 않는다. 장애 발생과 해제 알림 모두 전송한다.

설정 압축을 `umask 077`로 풀었으면 컨테이너가 설정을 읽도록 마운트할 YAML/JSON 파일은 0644, Grafana provisioning/dashboard 디렉터리는 0755로 조정한다. 배포 루트와 secrets 디렉터리는 0700으로 유지한다.

비밀값을 Git, 명령 출력, PR 본문에 남기지 않는다. 디렉터리와 파일은 운영 계정이 관리하고 컨테이너 실행 UID가 필요한 파일만 읽도록 권한을 설정한다. Docker 이미지별 UID가 달라 모든 파일을 일괄 600으로 두면 수집이 실패할 수 있다.

## API 활성화

### PR 902 병합 이후 정식 배포

#902가 포함된 정식 배포의 `infra/shinhan/compose.yaml`은 서버 `.env`의 `FGC_SPRING_PROFILES_ACTIVE`와 `FGC_MONITORING_SCRAPE_TOKEN`을 API에 전달한다. 서버 비공개 설정에서 프로필을 `prod,monitoring`으로 지정하고 기존 scrape 토큰을 보존한다. 프로필 값이 없으면 기본 `prod`이므로 토큰만 넣는 것으로는 전체 계측이 활성화되지 않는다.

다음 승격은 기존 정식 승인 경로를 사용한다. 재배포 후 별도 임시 override 없이 두 설정이 전달되는지, 실제 revision·무인증401·전용 인증200·공개 WEB404·두 target UP·JVM/HTTP/Hikari/배치 지표를 확인하고 기록한다. 이 문서 갱신은 재배포를 실행한 증거가 아니다.

### 이전 배포의 override 절차

기존 API 이미지가 #674를 포함함을 확인한 뒤 기존 운영 배포 파일과 비밀값을 백업한다. `infra/shinhan/monitoring.override.yaml`을 기존 앱 compose 뒤에 병합하고, 앱 배포용 secret 환경 파일에 `FGC_MONITORING_SCRAPE_TOKEN`을 제공한다. 기존 DB/JWT/이미지 설정을 그대로 읽어야 한다.

기존 운영 프로젝트의 compose 명령에 이 override를 추가하여 `config --quiet`로 검사한 뒤 API만 `up -d --no-deps api`로 갱신한다. 앱 프로필 변경은 API 재생성이 필요하며 잠깐의 중단이 발생할 수 있다. 실제 승격 파이프라인의 승인/버전 검증은 그대로 따른다. 후속 앱 배포에도 override를 포함하도록 배포 진입점을 함께 갱신해야 설정이 사라지지 않는다.

먼저 health UP, 무인증 scrape 401, 전용 토큰 scrape 200을 확인한다. 토큰은 비공개 설정 파일로 curl에 전달하여 프로세스 인자에 값이 노출되지 않도록 한다.

## 모니터링 실행

`FGC_APP_NETWORK`는 앞에서 확인한 실제 네트워크 이름으로 설정한다.

```bash
export FGC_APP_NETWORK=실제_API_네트워크_이름
docker compose -p fgc-monitoring -f compose.yaml -f shinhan/compose.yaml config --quiet
docker compose -p fgc-monitoring -f compose.yaml -f shinhan/compose.yaml up -d
```

서버의 Docker 서비스가 부팅 시 활성화되는지 확인한다. 각 컨테이너는 `restart: unless-stopped`이며 수동 stop 상태에서는 자동 재시작하지 않는다. 앱 배포 파이프라인과 달리 모니터링 스택을 앱 배포 때마다 재생성할 필요는 없다.

## 확인과 접속

- Prometheus targets의 fgc/fgc-health UP과 `probe_success=1`을 각각 확인한다.
- Grafana에 prod/shinhan의 JVM·Hikari·HTTP 지표가 표시되는지 확인한다. 배치 지표는 배치 실행 후 확인한다.
- 운영 지정 수신 서비스까지 테스트 알림의 전송/해제를 확인한다. 실제 prod DB/앱 중단 주입은 별도 점검 일정에서 수행한다.
- 운영 웹의 `/actuator/prometheus`가 외부에서 차단되는지 확인한다.
- UI는 서버 loopback의 13000(Grafana), 19090(Prometheus), 19093(Alertmanager)에만 바인딩한다. SSH 터널로 접속하거나 인증된 관리용 HTTPS 프록시를 별도로 구성한다. 공용 서비스 포트를 그대로 개방하지 않는다.

```bash
ssh -N -L 23000:127.0.0.1:13000 -L 29090:127.0.0.1:19090 -L 29093:127.0.0.1:19093 운영_SSH_별칭
```

개인 PC의 로컬 미리보기와 충돌하지 않도록 위 예시는 접속 포트를 다르게 사용한다. 접속 주소는 `http://127.0.0.1:23000`이다.

## 롤백

모니터링만 중지하려면 같은 디렉터리/네트워크 변수로 `docker compose -p fgc-monitoring -f compose.yaml -f shinhan/compose.yaml stop`을 실행한다. 데이터 볼륨은 보존한다. API 활성화까지 되돌려야 하면 해당 배포에서 보관한 기존 환경/compose를 복원하고 동일 운영 프로젝트에서 API만 갱신한다. DB 이미지나 볼륨은 변경하지 않는다.

같은 물리 서버가 중단되면 이 모니터링도 중단된다. 상시 전원 운영과 별개로 서버 외부 생존 감시가 있어야 이 경우까지 탐지할 수 있다.

## Discord 채널 준비

Discord 서버 설정 → 연동(Integrations) → Webhooks → 새 Webhook 생성에서 알림 전용 채널을 지정하고 URL을 복사한다. Webhook 관리 권한이 있는 사람이 생성해야 한다. URL은 해당 채널에 메시지를 보낼 수 있는 비밀값이다.

운영 서버의 `secrets/discord-webhook-url`에 URL만 한 줄로 저장한다. 실제 파일은 Git에서 제외되며 기본 미리보기 fixture 수신기와 별개다. 메시지에는 상태(FIRING/RESOLVED), 신한 prod, 경보명, 대상, 심각도, 배치명이 표시된다. 전송은 Alertmanager가 수행한다. Discord 채널에 접근할 수 있는 팀원이 확인하며 알림은 각자의 Discord 알림 설정을 따른다.

수신 검증은 운영 앱을 중단하는 대신 식별 가능한 테스트 경보를 Alertmanager에 주입하고, 실제 채널에서 발생/해제 메시지를 모두 확인한다. 이 전송은 해당 채널에 연결하기로 승인한 배포 작업의 일부이며 실제 업무 장애 경보와 구분해야 한다. 배치 경보의 해제는 최근 실패 감지창 종료일 수 있어 재처리 성공 증거로 취급하지 않는다.

## 운영 앱에 계측 코드가 아직 없는 경우

기존 운영 앱을 feature 브랜치 이미지로 교체하지 않고 health 감시만 먼저 배포할 수 있다. 이 단계에서는 `fgc-health` 수집과 health 실패 경보 및 Discord 전송만 유효하다. JVM/HTTP/배치 패널의 데이터, 오류율/배치 실패 경보와 metrics 수집 실패 경보는 활성화되지 않는다.

```bash
docker compose -p fgc-monitoring -f compose.yaml -f shinhan/compose.yaml -f shinhan/health-only.override.yaml up -d
```

`deployment.json`에 `phase: health-only`를 기록한다. 이는 전체 #674 계측 완료 상태가 아니다. #674가 포함된 앱 이미지가 검증된 경로로 배포된 후 API에 프로필·토큰을 적용하고 인증 scrape 200을 확인한 다음, health-only override를 제외하여 Prometheus를 재생성한다. `--remove-orphans`나 데이터 볼륨 삭제는 필요 없다. 이후 두 수집 대상 UP 및 HTTP/JVM/DB/배치 지표와 Discord까지 재검증하고 phase를 갱신한다.

배포 후 상태/시작/중지는 `bash shinhan/manage.sh ps`, `bash shinhan/manage.sh up -d`, `bash shinhan/manage.sh stop`을 사용하면 deployment.json의 현재 phase를 유지한다. 파일이 없거나 phase가 불명확하면 실행을 거부한다.
