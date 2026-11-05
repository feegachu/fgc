# 신한 운영 배포 확인 — 2026-11-04 KST

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
