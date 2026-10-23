# 파일 저장소·보안 연동 계약 (#637)

## 범위와 근거

ECR-006·SER-005와 OI-021의 **개인정보 없는 합성 문서만** 받는 2차 입력 보조 범위를 구현한다. 사용자 제공 요구사항 r19 및 2026-10-23 착수 승인을 기준으로 한다. SRC-006은 원문이 없는 상태이며, 이번 구현이 해당 외부 근거를 확보하거나 법적 적합성을 입증한 것은 아니다.

#632의 V58·V62를 재사용한다. 파일 저장소·검증·메타데이터·원본 파기 경로만 담당하며, 문서함 화면·IF-API-53 컨트롤러·미리보기 변환·추출 워커는 #640, 보존기간 확정·정기 파기 스케줄·백업은 #675/#676 범위다. 공개 파일 URL·정적 리소스 매핑은 추가하지 않는다.

## 호출 계약

| 호출 | 사용처 | 보장 |
|---|---|---|
| `IntakeFileService.upload(file, uploadedBy)` | #640 IF-API-53 | SETTLEMENT·GA_ADMIN·SYSTEM_ADMIN. 파일 검증 후 SHA-256으로 직렬화·중복 검사, UPLOADED/UNCLASSIFIED 메타데이터와 감사 기록. 사용자는 컨트롤러의 인증 주체에서 얻고 요청 본문으로 받지 않는다 |
| `IntakeFileService.withSource(id, reader)` | #640 추출·미리보기 | 내부 4역할만. 콜백이 끝날 때까지 공유 행 잠금과 스트림을 유지한다. 스트림을 콜백 밖으로 반환하거나 비동기 스레드로 넘기지 않는다. 만료 파기된 원본은 404 |
| `IntakeFileService.purgeExpired(limit)` | #675의 명시적 파기 작업 | SYSTEM_ADMIN. KST 오늘 이하의 purge_after만, EXTRACTING 제외. 1~1000개. 반환은 PurgeResult(selected, deleted, skipped, failed). failed가 있으면 작업 경고로 보고한다. 보존일을 자체 계산하거나 자동 실행하지 않는다 |
| `FileStorage.store/open/delete` | 내부 인프라·생성 PDF | 서버 생성 32자리 저장키만 사용. 클라이언트가 보낸 저장키·경로로 직접 호출하는 컨트롤러를 만들지 않는다. 생성 PDF는 신뢰된 생성기 출력만 저장하고 권한·판·해시는 해당 업무 서비스에서 관리한다 |

#640은 사람이 지정한 documentType·합성 문서 여부 확인과 문서 상태 전이를 담당한다. 상위 트랜잭션에서 upload를 호출하면 같은 트랜잭션에 참여하며, 뒤의 상태 변경·감사 저장이 실패해도 새 물리 파일이 정리된다. 같은 SHA-256은 409 `FGC-INTK-002`, `error.params.documentId`로 기존 문서를 안내한다. 이미 파기된 문서도 같은 해시는 새 문서로 만들지 않으므로 원본 없음 상태를 별도로 안내한다.

비동기 추출 워커는 요청의 내부 업무 권한을 전달하거나 승인된 서비스 계정으로 실행한다. 파기 작업 역시 SYSTEM_ADMIN 서비스 계정으로 명시적으로 실행하며, 인증 없는 스케줄러가 메서드 보안을 우회하도록 만들지 않는다.

원본 파일명은 표시용 문자열일 뿐 저장 경로가 아니다. API 응답·예외·로그에 절대 경로 또는 storage_key를 노출하지 않는다. 미리보기·다운로드 응답은 인증·권한 확인 후 지정된 MIME과 `X-Content-Type-Options: nosniff`를 사용하고 원본을 HTML로 인라인 제공하지 않는다.

## 허용 파일 정책

- pdf, xlsx, xls, csv, png, jpg/jpeg. 확장자·요청 MIME·실제 파서 결과가 모두 맞아야 한다. CSV는 Windows Excel의 application/vnd.ms-excel 및 application/csv 별칭도 받되 text/csv로 저장한다. MIME이 없거나 application/octet-stream이면 거절한다.
- 파일명에 경로 구분자·드라이브 구문·제어문자·끝 공백/점 등을 허용하지 않는다. 크기 0, 보고된 크기와 읽은 크기의 차이, 상한 초과도 400 `FGC-INTK-001`이다.
- 서블릿 multipart 파싱 중 파일/요청 상한을 넘으면 컨트롤러 선택 전에도 ApiMultipartExceptionResolver가 공통 핸들러를 재사용해 같은 400 JSON 봉투로 반환한다. API가 아닌 경로와 다른 예외는 기존 MVC/MPA 처리로 넘긴다. 실제 Tomcat HTTP에서 두 상한을 각각 검증한다.
- PDFBox로 암호화·스크립트·자동 동작·첨부·외부 실행 동작을 거절한다. 순환 참조는 방문 집합으로 처리한다.
- POI로 실제 xls/xlsx 구조를 확인한다. 합성 입력은 **값 전용** 정책이다: 수식·정의된 이름·매크로·외부 링크·내장 객체를 거절한다. 원본을 임의로 고쳐 저장하지 않는다. zip은 중복/경로 조작 엔트리, 4096개 초과 또는 전개 64MiB 초과를 거절하고 셀은 100만 개를 상한으로 둔다.
- CSV는 UTF-8(BOM 허용), 제어문자 및 수식 주입을 거절한다. 음수 등 부호 있는 숫자는 허용한다. CP949 입력은 변환한 합성 UTF-8 파일로 다시 올려야 한다.
- 이미지는 실제 디코더·MIME을 대조하며 2천만 픽셀을 넘지 않는다.

이는 형식·능동 콘텐츠 제한이며, 모든 악성코드 탐지를 보장하는 안티바이러스가 아니다. 실문서·개인정보·신뢰하지 않는 외부 임의 문서를 허용하는 범위 확장은 별도 승인·격리 검사 설계가 필요하다. 파서는 최신 보안 업데이트를 추적한다. 버전 근거: [Apache POI 다운로드](https://poi.apache.org/download.html), [Apache PDFBox 다운로드](https://pdfbox.apache.org/download.html).

## 파일·DB 실패 처리

업로드는 검증 → 해시별 PostgreSQL advisory 잠금 → 기존 문서 조회 → CREATE_NEW 저장 → 메타데이터·감사 INSERT 순서다. 메타데이터·감사·상위 트랜잭션 실패 시 TransactionSynchronization으로 새 파일을 삭제한다. 파일 삭제 실패는 운영 오류로 기록하며 숨기지 않는다. 프로세스 강제 종료 시 커밋 전 파일이 남을 수 있으므로 임의 전체 삭제 대신, 재기동 뒤 DB의 storage_key와 비교한 **미참조 파일 목록을 점검·승인 후 정리**한다. 이 고아 파일 운영 점검과 백업은 #675/#676에 연결한다.

원본 파기는 두 체크포인트를 사용한다(V65):

1. 전용 REQUIRES_NEW 트랜잭션에서 만료 행을 `FOR UPDATE SKIP LOCKED`로 선택하고 source_purged_at 및 파기 감사 기록을 함께 커밋한다. 이 트랜잭션이 실패하면 파일은 건드리지 않는다.
2. 커밋 후 별도 트랜잭션에서 파기 대기 행을 잠그고 파일을 삭제한 뒤 source_deleted_at을 기록한다. 동시 파기 작업은 잠긴 행을 건너뛴다.
3. 파일 삭제 또는 확인 DB 커밋 실패 시 source_purged_at은 남아 접근이 차단되고 source_deleted_at이 비어 있어 재시도한다. 이미 파일이 없어도 삭제 완료로 확인하며 감사는 중복하지 않는다.

각 삭제 행의 실패는 격리해 뒤 행을 계속 처리한다. 1단계에서 source_delete_attempted_at·source_delete_attempts를 기록하고, 미시도 행을 먼저, 이후 가장 오래 시도한 행 순으로 선택한다. 따라서 A가 영구 실패해도 limit=1의 다음 호출에서 B가 선택되며, 같은 묶음에서도 B의 삭제를 막지 않는다. 경로·내용 없이 문서 ID와 오류 유형만 로그에 남기고 호출자는 failed 건수를 반드시 확인한다. deleted는 실제 삭제 또는 이미 없음까지 확인·커밋한 문서 수, skipped는 다른 파기 작업이 점유·완료한 문서 수다.

추출·확정 자료와 감사 이력은 지우지 않는다. 파기는 상위 업무 트랜잭션의 롤백과 독립적으로 커밋되는 명시적 비가역 작업이며, 복구를 약속하는 일반 업무 저장 API에 섞지 않는다. source_purged_at이 있는 문서는 재추출을 시작하지 않는다. 워커는 withSource를 통해 원본을 소비하고 EXTRACTING 상태 전이를 행 잠금과 함께 수행해야 한다.

## 배포·Runbook 반영 요청 (#676)

- `FGC_STORAGE_ROOT` 기본값 `./files`는 로컬 개발용이다. 배포에서는 `/var/lib/fgc/files` 같은 **앱 이미지 밖의 영속 전용 볼륨**으로 지정하고 모든 앱 인스턴스가 같은 DB·같은 볼륨을 사용한다. HTTP/Nginx/Tomcat의 document root나 정적 리소스 경로로 연결하지 않는다.
- 볼륨은 앱 UID/GID 소유, 디렉터리 0700·파일 0600, 가능하면 noexec/nosuid/nodev. Windows는 소유자 전용 ACL로 파일 EXECUTE를 제외한다. POSIX·ACL 모두 지원하지 않으면 저장을 거절한다. 새 컨테이너에서도 같은 볼륨과 UID/GID를 유지해야 한다.
- 기본값: `FGC_UPLOAD_MAX_BYTES=20971520`, `FGC_UPLOAD_MAX_SIZE=20MB`, `FGC_UPLOAD_MAX_REQUEST_SIZE=21MB`. 앞 두 값은 같은 상한으로 운영하고 요청 상한에는 multipart 여유를 둔다. 앱 상한은 50,000,000바이트 미만만 허용한다. 프록시 상한도 함께 맞춘다.
- 재배포 전후 저장키의 파일 바이트·SHA-256을 비교하고 인증 없는 직접 접근이 막히는지 확인한다. V65는 새 마이그레이션이며 V58·V62 등 기존 파일은 수정하지 않는다.
- DB와 파일을 함께 백업하되, 파기 체크포인트가 있는 원본은 복구 후에도 공개하지 않는다. 백업 원본 보존·물리 파기는 #675에서 별도로 결정한다. 이 PR은 AWS·Podman·운영 데이터에 접속하거나 배포하지 않는다.

## 재현 테스트

`gradlew.bat test --tests '*UploadFileValidatorTest' --tests '*PrivateVolumeFileStorageTest' --tests '*IntakeFileServiceIntegrationTest' --tests '*MultipartLimitHttpIntegrationTest' --tests '*ApiMultipartExceptionResolverTest' --tests '*GlobalExceptionHandlerTest'`

통합 테스트는 별도 일회성 PostgreSQL `fgc_files_637`과 `build/tmp/intake-637-*`만 사용한다. 원본 내용·메타데이터·감사, 동시 중복, 상위 롤백, 감사 실패, 보존기한·EXTRACTING 제외, 읽기/파기 경합, 삭제 실패 재시도, 작은 배치 반복을 확인한다. POSIX 권한·symlink 검사는 Linux CI에서, ACL 검사는 Windows에서 수행한다. 같은 볼륨의 다른 저장소 인스턴스에서 읽는 테스트가 재기동 보존을 검증하며 실제 배포 서버의 재배포·백업 리허설을 대신하지 않는다.

RTM ECR-006·SER-005 구현모듈·테스트케이스 칸을 이 계약과 PR 증빙으로 갱신 요청한다. 사람 2인 리뷰·배포 증빙 등 남은 인수 조건 없이 인수완료로 표시하지 않는다.
