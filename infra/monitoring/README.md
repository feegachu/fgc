# FGC 관측성 (#674)

최신 develop dd1ba27b에서 분리. ECR-007/PER-006의 HTTP·JVM·DB 연결 풀·배치 지표와 장애 알림을 구성한다.
요구사항 원문은 https://github.com/feegachu/fgc/issues/674 에 인용되어 있다. 이슈의 SRC-005·SRC-006 원문 누락 표기는 이번 구현으로 해결하지 않는다. 규제 판단·금액 산식은 변경하지 않는다.

## 구성과 실행

Spring Boot Actuator + Micrometer → Prometheus → Grafana.
Prometheus 경보 → Alertmanager → 지정 webhook 수신 서비스.
Blackbox Exporter는 /actuator/health의 HTTP 상태와 UP 본문을 확인하므로 DB health 실패도 감지한다.
DB 지표는 Hikari 연결 풀(active/idle/pending/max)과 DB 연결 health이며 PostgreSQL 전체 쿼리 통계 exporter를 설치하는 범위는 아니다.

1. 앱의 기존 배포 프로필에 `monitoring`을 추가한다(예: `SPRING_PROFILES_ACTIVE=prod,monitoring`). 기존 DB/서버 설정을 유지한다.
2. 충분히 긴 임의 비밀값을 `FGC_MONITORING_SCRAPE_TOKEN`으로 앱에 제공한다. 동일 값을 이 디렉터리의 `secrets/scrape-token` 파일에 개행 없이 저장한다. 비어 있으면 수집은 401로 거부된다.
3. `secrets/grafana-password`에 별도의 관리자 비밀번호, `secrets/webhook-url`에 Alertmanager **generic webhook** 형식을 받는 운영 수신 URL을 저장한다. Slack/Teams Incoming Webhook에 직접 연결하는 형식이 아니며 해당 서비스를 쓰면 Alertmanager receiver를 그 서비스 전용으로 변경한다.
4. `targets.json`의 주소/환경을 실제 API 내부 주소로 바꾼다. 기본값은 Docker Desktop 호스트의 8081. 두 scrape가 같은 대상을 사용한다. 파일에는 scheme을 넣지 않는다.
5. `docker compose -f infra/monitoring/compose.yaml up -d` (저장소 루트에서 실행).
6. Grafana http://127.0.0.1:13000 에 admin/설정 비밀번호로 로그인한다. FGC/FGC Operations 대시보드와 Prometheus datasource가 자동 등록된다. Prometheus는 19090, Alertmanager는 19093이다.

수집 경로는 신뢰된 내부망 전용으로 제한한다. 기본 예제는 HTTP 내부 통신이며 외부/비신뢰 네트워크를 건너면 TLS를 설정한다. HTTPS 타깃은 prometheus.yml의 fgc `scheme: https`와 health replacement의 https를 함께 변경한다. UI 포트는 loopback 바인딩이므로 원격 운영 시 SSH 터널 또는 인증된 역방향 프록시를 사용한다. secrets는 Git에서 제외되며 파일 소유권/읽기 권한을 운영자가 제한한다.

업무 JWT로 prometheus를 읽거나 scrape 토큰으로 업무 API를 호출할 수 없다. endpoint 노출은 health/prometheus뿐이다. 관리 endpoint는 업무 Swagger 명세에 추가하지 않는다.

## 지표와 로그

- HTTP 요청/5xx/응답시간 히스토그램, JVM 메모리, process uptime, Hikari 지표: Boot 기본 계측.
- `fgc_batch_job_seconds_*`, `fgc_batch_step_seconds_*`: 완료 상태별 소요시간/실행 수.
- `fgc_batch_items_total`: Job/Step별 read/written/skipped. tasklet의 업무 건수는 해당 StepExecution에 기록되는 건수만 반영하며 임의로 DB를 조회하지 않는다.
- `fgc_batch_last_failure_timestamp`: 가장 최근 실패 시각. 첫 수집 전에 실패하거나 곧바로 재시도 성공해도 120초 동안 실패 알림 가능.
- Spring 관리 Job/Step에 listener를 자동 연결한다. 파티션 이름의 `:` 이후는 metric label에서 제외한다. 실행 ID·고객·계약·기준월·예외 메시지는 label에 넣지 않는다.
- Prometheus의 scrape job=fgc와 배치 job 이름이 겹치므로 배치 이름은 수집 결과의 `exported_job`이다.
- monitoring 프로필은 ECS JSON console 로그를 사용한다. 배치 로그의 event/job/step/status/read/written/skipped와 기존 requestId를 조회한다. 로그 저장/검색 시스템은 기존 배포 로그 수집 경로를 사용한다.

## 경보와 한계

스크레이프 10초·타임아웃 5초·평가 5초·health/scrape 오류 유지 10초·초기 묶음 대기 5초.
일반적인 건강/배치 실패는 이 설정으로 60초 내 전송을 목표로 한다. 오류율은 30초 관측창에서 5% 초과가 10초 유지될 때 알린다. 이미 0인 counter가 필요하며 요청 발생량/수집 위상에 따라 임계값 도달시간이 달라진다.
경보는 alertname/instance/배치별로 묶고 같은 지속 장애는 4시간마다 반복한다. 새 이벤트는 group_interval 10초 뒤 전달한다. 모든 독립 실패마다 메시지를 보내는 감사 로그가 아니다.
외부 수신 채널 장애·네트워크·알림 서비스 중단은 60초를 보장할 수 없으므로 배포환경에서 실제 장애 주입과 수신시간을 인수 검증해야 한다. 자체 모니터링 전체 장애는 외부 uptime 감시로 별도 보완한다.

## 재현 가능한 검증 (운영 자원 불사용)

```powershell
./gradlew.bat test --tests '*MonitoringEndpointTest' --tests '*BatchMetrics*Test' bootJar
# 아래 project name은 테스트 전용. 기존 fgc-monitoring 운영 프로젝트와 구분한다.
docker compose -p fgc-monitoring-674-test -f infra/monitoring/compose.yaml -f infra/monitoring/smoke/compose.yaml up -d
python infra/monitoring/smoke/check.py
docker compose -p fgc-monitoring-674-test -f infra/monitoring/compose.yaml -f infra/monitoring/smoke/compose.yaml down -v
```

테스트 fixture의 공개 비밀번호/토큰은 격리된 합성 서버용이다. 로컬 수신기로만 알림을 보낸다. 실제 Prometheus/Alertmanager/Blackbox/Grafana를 사용하고 DB DOWN health, 배치 실패, 오류율 급증, 수집 실패를 주입한다. `infra/monitoring/smoke-results.json`에 실제 알림 도착시간과 반복 억제 검증을 기록한다. Grafana 대시보드 12개 패널의 자동 등록도 API로 검사한다. DB DOWN은 합성 health 응답이며 운영 DB 중단 시험이 아니다.

정상 health·미인증 차단·토큰 격리·JVM/uptime 계측은 실제 Spring Actuator와 production SecurityConfig의 HTTP 테스트로 검증한다. 배치 failure listener 연결은 실제 Spring Batch Job/Step 실행으로 검사한다.

## 운영 반영/롤백 및 완료 조건

- PR 병합과 실제 배포는 별도. 지정 알림 채널·서버별 targets·secret 주입 후 운영 담당자가 60초 수신을 확인한다.
- 중지: `docker compose -f infra/monitoring/compose.yaml stop`. 앱 monitoring 프로필을 제거하면 지표 HTTP 노출/JSON 로그가 기본값으로 돌아간다. 데이터 볼륨은 보존한다.
- RTM ECR-007/PER-006에 구현모듈·테스트 근거 갱신 요청. 사람 리뷰 2인, 운영 수신 증거와 미확인 SRC 원문은 이슈 완료 전 남은 항목이다.
- 공식 참조: https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html , https://prometheus.io/docs/alerting/latest/configuration/ .

신한 prod 내부망 배포·검증·SSH 터널·롤백 절차: [shinhan/README.md](shinhan/README.md).
