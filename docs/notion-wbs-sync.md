# PR merge → Notion WBS 자동 반영

`Sync Notion WBS`는 2차 기능 요구사항 DB의 `GitHub 이슈` URL로 이슈를 식별한다.
FUN 번호나 PR 제목·본문의 일반적인 `#번호` 언급으로 완료를 추정하지 않는다.

## 동작

- develop을 대상으로 하는 PR이 merge되거나 이슈가 closed/reopened되면 전체 추적 행을 재확인한다.
- GitHub `closedByPullRequestsReferences`의 PR이 이 저장소의 develop에 실제 merge됐고,
  이슈도 `CLOSED / COMPLETED`이면 해당 Notion 행의 `상태`를 `완료`로 바꾼다.
- PR 본문에 `Closes #632`처럼 종료 연결을 명시해야 한다. 참조만 있는 문서 PR은 완료 근거가 아니다.
- 이슈를 reopen하면 이미 완료인 Notion 행을 `진행 중`으로 되돌린다.
- 시작 전/진행 중인 수동 작업 상태, 담당자, WBS 관계, 상세 본문은 보존한다.
- WBS 진행률은 기존 관계형 롤업(`기능요구사항 → 상태 → 완료 비율`)이 자동 계산한다.
  코드가 진행률 숫자나 롤업 정의를 직접 쓰지 않는다.
- 새 이슈의 Notion 행은 자동 생성하지 않는다. 팀이 행과 담당 WBS를 추가하고 GitHub URL을 넣으면 된다.
- DB 행 전체를 재확인하므로 실패한 실행을 재실행하거나 Actions에서 수동 실행하면 과거 merge도 소급 반영한다.
- 동일 이슈 URL이 여러 행에 있거나 완료 상태 그룹이 잘못되면 변경 전에 오류로 중단한다.

## 소유자가 한 번 설정할 내용

1. [Notion 연결 관리](https://www.notion.so/profile/integrations)에서 FGC 팀 워크스페이스의
   내부 API 토큰 연결 `FGC WBS Sync`를 생성한다. 콘텐츠 읽기·업데이트 권한이 필요하다.
2. 2차 스프린트 계획 페이지 `••• → 연결 추가`에서 이 연결을 추가한다.
   하위 기능 요구사항 DB와 WBS에 접근 가능해야 한다. 실제 DB가 다른 위치에 있으면 원본 DB에도 연결한다.
3. [저장소 Actions secrets](https://github.com/feegachu/fgc/settings/secrets/actions)에
   repository secret `NOTION_TOKEN`으로 토큰을 저장한다. 채팅·문서·코드·PR에는 토큰을 쓰지 않는다.
4. 기본 브랜치 develop에 이 워크플로를 배포한다.
5. GitHub Actions → **Sync Notion WBS → Run workflow**, branch **develop**, dry_run **true**로
   접근 권한·스키마·예정 변경을 확인한다. 성공하면 dry_run **false**로 실행해 과거 완료 이슈를 반영한다.
6. Notion WBS에서 실제 완료율을 확인한다. #632는 PR #684의 merge 근거로 완료이고,
   다른 완료가 없다면 손가영은 1/13 = 약 7.69%다.

기능 요구사항 DB ID는 `37b2f20a-c4dd-8210-b93b-01c8ea93ec5c`이며 API로 하위 데이터 소스를 찾는다.
여러 소스가 있으면 저장소 Actions variable `NOTION_WBS_DATA_SOURCE_ID`에 대상 소스 ID를 넣는다.
이는 비밀이 아니다. DB 설정의 데이터 소스 관리에서 ID를 복사할 수 있다.

## 오류와 재실행

- `NOTION_TOKEN is missing`: secret 등록이 필요하다. 배포만으로 연동이 켜지지 않는다.
- HTTP 403/404: 연결이 해당 팀 워크스페이스·원본 DB에 접근할 수 있는지 확인한다.
- 이슈가 완료인데 상태가 안 바뀜: 이슈를 종료한 PR의 `Closes/Resolves/Fixes` 연결과 실제 merge,
  develop 대상 여부를 확인한다. 단순 수동 close, not-planned close, 다른 기능 PR은 완료 근거가 아니다.
- API 429/일시적 서버 오류는 제한된 횟수로 재시도한다. 실패한 실행은 실패로 남아 확인할 수 있다.
- 실행은 멱등적이다. 재실행해도 행·댓글·본문을 중복 생성하지 않는다.
- 비밀을 쓰는 작업은 기본 브랜치 코드를 checkout하고 PR head·artifact·본문 코드를 실행하지 않는다.
  GitHub 권한은 contents/issues/pull-requests 읽기로 제한한다.

검증: `python -B -m unittest discover -s .github/scripts -p 'test_notion_wbs_sync.py' -v`

참고: [Notion data source 조회](https://developers.notion.com/reference/query-a-data-source),
[페이지 상태 수정](https://developers.notion.com/reference/patch-page),
[GitHub pull_request_target 보안](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target).
