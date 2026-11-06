# 신한 운영 배포와 관측성 인수 현황

## 2026-11-06 현재 상태

아래 11월4일 기록은 이력이다. [#674 운영 인수](https://github.com/feegachu/fgc/issues/674#issuecomment-6656614578)에 따라 phase는 full로 전환됐다. 신한 배포10 SUCCESS/revision `7c1733de`, 무인증401·전용 인증200·공개 WEB404, Prometheus fgc/fgc-health UP, JVM/HTTP/Hikari 수집을 확인했다.

[실제 배치 인수](https://github.com/feegachu/fgc/issues/674#issuecomment-6657644337): 2026-07 run11 COMPLETED, 논리 단계8, Job3.88264909초, Job1/Step11, itemsWritten104. 업무 예외43건을 배치 실패로 세지 않는다. Discord 테스트는 발생6,013ms/해제10,025ms, 성공2/실패0이다. 이 기록은 실제 운영 장애 주입을 대신하지 않는다.

#868은 11월5일, #902는 11월6일 병합됐다. #902 최종 HEAD `ffa9c1dc`의 build/frontend/playwright/real-server/UI/negative/Sonar/CodeQL은 성공했다([E2E](https://github.com/feegachu/fgc/actions/runs/42139132503)). 운영에 #902까지 배포됐다는 의미는 아니다.

기존 격리 Compose smoke는 경보4종 60초 수신·중복 억제를 검증한다. 후속 검증은 합성 정상 복구·4종 resolved 전달·잔여 경보0도 검사하고 CI artifact에 환경과 측정값을 남긴다. 합성 DB DOWN을 실제 PostgreSQL 종료로 기록하지 않는다.

2026-11-06 후속 코드 `4c5b88cf`의 로컬 격리 실측은 health25.23초·batch19.11초·5xx24.01초·scrape19.91초이며 중복0·resolved4종·잔여 경보0·Grafana12패널을 통과했다. 같은 HEAD의 [monitoring CI](https://github.com/feegachu/fgc/actions/runs/42144382465)도 성공했다. 이후 develop의 #905를 통합했으므로 최종 HEAD 전체 CI는 해당 커밋 기준으로 따로 확인한다.

### 최종 인수에 필요한 항목

1. 다음 정식 배포에서 #902 설정만으로 프로필·토큰 유지, 인증·target UP·지표 수집을 확인하고 배포 전후 revision을 기록한다.
2. PM과 실제 배포환경 장애 주입 인수 범위·작업창을 확정한 뒤 주입→지정 채널 도착60초·복구를 측정한다.
3. SRC-005/006 원문, 도메인 담당+PL/PM 2인 리뷰, RTM·graphify 증거를 연결한다. 서로 다른 PR의 승인1건씩을 각 PR의 2인 승인으로 합산하지 않는다.

금융 영속성 변경은 없으므로 잠금·금액·롤백 Testcontainers 재검증은 해당 없음이다. 위 인수 증거가 남아 #674는 자동 종료하지 않는다.

## 2026-11-04 당시 기록

현재 배포 phase: **health-only**. 전체 #674 계측 배포 완료를 뜻하지 않는다.

- 별도 `fgc-monitoring` Compose 프로젝트로 Prometheus, Blackbox, Alertmanager, Grafana 기동.
- 기존 운영 API/WEB/DB 이미지와 데이터는 변경하지 않음. 기존 API commit `ecd6ea2710faf3cedfa197fc6f005ff18d68c56b` 유지.
- 운영 이미지에 #674의 exporter/인증 설정이 없어 전체 지표 수집은 비활성. PR 미머지 조건 유지.
- 실제 내부망 health probe `probe_success=1`, API health UP.
- Grafana datasource와 대시보드 12개 패널 등록 확인. JVM/HTTP/배치 패널은 계측 앱 배포 전에는 데이터가 없음.
- 신한 서버 Alertmanager → 실제 Discord의 테스트 발생/해제 알림 2건 전송 성공, 전송 실패 0, 종료 후 활성 경보 0.
- Docker 부팅 활성화 `enabled`, 모니터링 4개 컨테이너 `unless-stopped` 확인.
- 메모리 상한: Prometheus 512MiB, Blackbox 64MiB, Alertmanager 128MiB, Grafana 256MiB.
- 관리 포트는 서버 loopback 13000/19090/19093에 바인딩. 공개 WEB의 `/actuator/prometheus`는 404 확인.
- 앱/DB 중단 장애 주입은 운영 서버에서 하지 않음. Discord 테스트는 식별 가능한 `FgcNotificationTest`로 수행.

서버 상태/시작/중지: 배포 디렉터리에서 `bash shinhan/manage.sh ps`, `up -d`, `stop` 사용. 현재 phase를 보존한다.

전체 계측 활성화는 #674 포함 이미지의 정식 배포, API monitoring 프로필·scrape 토큰 설정, 인증 수집 확인 후 health-only override 제거 순서로 진행한다. 서버 `deployment.json`에 실제 이미지와 검증 상태를 기록했다. 비밀번호와 Webhook URL은 이 문서나 Git에 포함하지 않는다.
