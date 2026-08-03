# FGC 기여 가이드

FGC 프로젝트의 브랜치, 커밋, Pull Request, 코드 리뷰 및 데이터베이스 변경 규칙입니다.

모든 팀원은 작업을 시작하기 전에 본 문서를 확인하고 동일한 규칙을 적용합니다.

---

## 1. 브랜치 전략

FGC 프로젝트는 간소화된 Git Flow 전략을 사용합니다.

| 브랜치          | 역할               | 규칙                                     |
| ------------ | ---------------- | -------------------------------------- |
| `main`       | 발표·배포 가능한 안정 버전  | 직접 push 금지, PR만 허용                     |
| `develop`    | 다음 릴리즈를 위한 기능 통합 | feature 브랜치가 병합되는 대상                   |
| `feature/*`  | 신규 기능 개발         | `develop`에서 분기하여 `develop`으로 병합        |
| `fix/*`      | 일반 버그 수정         | 수정 대상 브랜치에서 분기                         |
| `release/*`  | QA 및 릴리즈 준비      | `develop`에서 분기하여 `main`과 `develop`에 병합 |
| `hotfix/*`   | 배포 버전의 긴급 오류 수정  | `main`에서 분기하여 `main`과 `develop`에 병합    |
| `refactor/*` | 기능 변경 없는 코드 개선   | 일반적으로 `develop`으로 병합                   |
| `docs/*`     | 문서 작성 및 수정       | 일반적으로 `develop`으로 병합                   |

기본 흐름은 다음과 같습니다.

```text
feature/12-contract-create
        ↓ PR
     develop
        ↓
release/v1.0-phase1
        ↓
       main
```

다음 브랜치에는 직접 push하지 않습니다.

```text
main
develop
```

반드시 별도의 작업 브랜치를 생성하고 Pull Request를 통해 병합합니다.

---

## 2. 브랜치 이름 규칙

브랜치 이름은 영문 소문자와 하이픈을 사용합니다.

```text
feature/{이슈번호}-{도메인}-{작업내용}
fix/{이슈번호}-{도메인}-{설명}
hotfix/{이슈번호}-{설명}
refactor/{이슈번호}-{도메인}-{설명}
docs/{이슈번호}-{설명}
release/{버전}
```

### 작성 예시

```text
feature/12-contract-create
feature/23-cap-validation
feature/31-settlement-batch

fix/41-auth-login-error
fix/42-contract-duplicate-check

hotfix/51-settlement-amount-error

refactor/61-contract-service-structure

docs/71-api-definition-update

release/v1.0-phase1
```

### 도메인 이름 예시

```text
common
auth
base
policy
contract
transaction
schedule
cap
arbitrage
ledger
reconciliation
exception
validation
audit
dashboard
batch
```

브랜치 이름의 이슈 번호는 GitHub Issue 번호를 사용합니다.

---

## 3. 커밋 메시지 규칙

FGC 프로젝트는 Conventional Commits 형식을 사용합니다.

```text
<type>(<scope>): <subject>
```

필요한 경우 본문과 이슈 정보를 추가합니다.

```text
<type>(<scope>): <subject>

<body>

<footer>
```

### type 종류

| type       | 사용 목적                      |
| ---------- | -------------------------- |
| `feat`     | 신규 기능 추가                   |
| `fix`      | 버그 수정                      |
| `refactor` | 기능 변경 없는 코드 구조 개선          |
| `test`     | 테스트 코드 추가 또는 수정            |
| `docs`     | 문서 추가 또는 수정                |
| `chore`    | 환경설정, 빌드, 의존성 등의 기타 작업     |
| `style`    | 공백, 정렬, 포맷 등 코드 동작과 무관한 수정 |
| `perf`     | 성능 개선                      |
| `ci`       | GitHub Actions 등 CI 설정 변경  |
| `build`    | Gradle 및 빌드 설정 변경          |

### scope 예시

```text
common
auth
policy
contract
schedule
cap
ledger
reconciliation
batch
db
```

### 커밋 메시지 예시

```text
feat(contract): 계약 등록 API 구현
```

```text
feat(cap): 1200% 한도 검증 기능 구현
```

```text
fix(auth): 로그인 실패 횟수 초기화 오류 수정
```

```text
refactor(policy): 정책 조회 로직 분리
```

```text
test(contract): 계약 중복 등록 테스트 추가
```

```text
docs: 개발환경 구축 가이드 추가
```

```text
chore(db): Flyway 의존성 추가
```

```text
ci: Pull Request 빌드 워크플로 추가
```

### 상세 커밋 예시

```text
feat(batch): 월 정산 배치 멱등성 처리

- 동일 정산월 중복 실행 방지
- 배치 실행 이력 저장
- 실패 건 재실행 처리 추가

Related to #12
```

### 커밋 제목 작성 규칙

* 제목은 한글로 작성할 수 있습니다.
* 제목 끝에 마침표를 붙이지 않습니다.
* 무엇을 변경했는지 명확하게 작성합니다.
* `수정`, `작업`, `변경사항`처럼 의미가 불명확한 제목은 피합니다.

잘못된 예시:

```text
수정
코드 변경
작업 완료
최종 수정
```

권장 예시:

```text
fix(cap): 초년도 지급액 합산 오류 수정
feat(contract): 계약 상태 이력 조회 API 구현
```

---

## 4. 기본 작업 흐름

### 4.1 `develop` 브랜치 최신화

작업을 시작하기 전에 원격 `develop` 브랜치를 최신 상태로 맞춥니다.

```bash
git checkout develop
git pull origin develop
```

### 4.2 GitHub Issue 생성 또는 확인

작업에 해당하는 GitHub Issue 번호를 확인합니다.

예시:

```text
#12 계약 등록 API 구현
```

### 4.3 작업 브랜치 생성

```bash
git checkout -b feature/12-contract-create
```

### 4.4 기능 구현

기능을 구현하고 작은 단위로 커밋합니다.

```bash
git add .
git commit -m "feat(contract): 계약 등록 API 구현"
```

### 4.5 원격 저장소에 push

```bash
git push origin feature/12-contract-create
```

최초 push 시 다음 명령어를 사용할 수 있습니다.

```bash
git push -u origin feature/12-contract-create
```

### 4.6 Pull Request 생성

GitHub에서 다음 방향으로 Pull Request를 생성합니다.

```text
feature/12-contract-create
        ↓
develop
```

로컬에서 직접 `develop`에 merge하지 않습니다.

---

## 5. Pull Request 규칙

Pull Request를 생성할 때 `.github/PULL_REQUEST_TEMPLATE.md` 양식을 작성합니다.

### 기본 규칙

* PR 대상 브랜치를 확인합니다.
* 관련 GitHub Issue 번호를 작성합니다.
* 구현 내용과 테스트 방법을 구체적으로 작성합니다.
* 최소 1명의 리뷰어를 지정합니다.
* 본인이 작성한 PR은 본인이 승인하지 않습니다.
* CI 빌드와 테스트가 성공해야 합니다.
* 리뷰 의견을 반영한 후 병합합니다.
* 해결되지 않은 리뷰 대화가 남아 있으면 병합하지 않습니다.

### 관련 이슈 작성 예시

기능 PR이 `develop`으로 병합될 때는 다음처럼 이슈를 연결합니다.

```text
Related to #12
```

이슈를 실제로 종료해야 하는 최종 PR에서는 다음 형식을 사용할 수 있습니다.

```text
Closes #12
```

또는:

```text
Resolves #12
```

### PR 제목 예시

```text
[CONTRACT] 계약 등록 API 구현
```

```text
[CAP] 1200% 검증 기능 구현
```

```text
[FIX] 로그인 실패 횟수 초기화 오류 수정
```

---

## 6. 코드 리뷰 규칙

리뷰어는 다음 항목을 확인합니다.

### 공통 확인사항

* 요구사항과 화면정의서에 맞게 구현했는가
* 코드가 담당 도메인 구조를 따르는가
* 불필요한 중복 코드가 없는가
* 예외 처리가 누락되지 않았는가
* 민감정보가 포함되지 않았는가
* 테스트 방법이 명확한가
* 기존 기능에 영향을 주지 않는가

### 백엔드 확인사항

* Controller에 업무 로직이 들어가 있지 않은가
* Service와 Mapper의 책임이 분리되어 있는가
* DTO와 Entity 또는 DB 모델을 구분했는가
* 입력값 검증이 적용되어 있는가
* 트랜잭션 범위가 적절한가
* 오류 코드와 공통 예외 처리를 사용했는가
* 금액 계산에서 원 단위 반올림 규칙을 지켰는가

### 데이터베이스 확인사항

* 기존 Flyway 파일을 수정하지 않았는가
* 새로운 버전 번호를 사용했는가
* 제약조건과 인덱스가 필요한지 검토했는가
* 기존 데이터에 미치는 영향을 작성했는가
* 롤백 또는 복구 방법을 검토했는가

---

## 7. Merge 전략

브랜치 유형에 따라 병합 방식을 구분합니다.

| 병합 방향                | 방식                    |
| -------------------- | --------------------- |
| `feature → develop`  | Squash and merge      |
| `fix → develop`      | Squash and merge      |
| `refactor → develop` | Squash and merge      |
| `docs → develop`     | Squash and merge      |
| `release → main`     | Create a merge commit |
| `release → develop`  | Create a merge commit |
| `hotfix → main`      | Create a merge commit |
| `hotfix → develop`   | Create a merge commit |

### Squash and merge

feature 브랜치의 여러 커밋을 하나의 커밋으로 정리하여 `develop`에 병합합니다.

Squash 커밋 제목도 커밋 메시지 컨벤션을 따릅니다.

```text
feat(contract): 계약 등록 기능 구현
```

### Merge commit

릴리즈 및 긴급 수정 이력을 보존하기 위해 merge commit을 사용합니다.

---

## 8. Rebase 규칙

개인 작업 브랜치에서 최신 `develop` 내용을 반영할 때 rebase를 사용할 수 있습니다.

```bash
git checkout feature/12-contract-create
git fetch origin
git rebase origin/develop
```

충돌이 발생한 경우 충돌을 해결합니다.

```bash
git add .
git rebase --continue
```

rebase를 취소하려면 다음 명령어를 사용합니다.

```bash
git rebase --abort
```

이미 원격에 push한 개인 브랜치를 rebase했다면 다음 명령어를 사용합니다.

```bash
git push --force-with-lease origin feature/12-contract-create
```

일반 강제 push는 사용하지 않습니다.

```bash
git push --force
```

공유 브랜치에서는 rebase하지 않습니다.

```text
main
develop
release/*
팀원이 공동으로 사용하는 feature 브랜치
```

---

## 9. 충돌 해결 규칙

충돌이 발생하면 해당 코드를 작성한 팀원과 확인한 후 해결합니다.

### 기본 순서

```bash
git fetch origin
git rebase origin/develop
```

충돌 파일을 수정한 다음:

```bash
git add 충돌파일
git rebase --continue
```

충돌 해결 후에는 반드시 다음을 확인합니다.

```bash
gradlew.bat clean build
```

macOS 또는 Linux:

```bash
./gradlew clean build
```

애플리케이션 실행도 확인합니다.

```bash
gradlew.bat bootRun
```

### 충돌 시 금지사항

* 상대방 코드를 확인하지 않고 삭제하지 않습니다.
* `main` 또는 `develop`에 강제 push하지 않습니다.
* 충돌 표시가 남은 상태로 커밋하지 않습니다.

충돌 표시 예시:

```text
<<<<<<< HEAD
=======
>>>>>>> develop
```

---

## 10. Flyway 및 DDL 변경 규칙

FGC 프로젝트는 Flyway를 통해 데이터베이스 스키마를 관리합니다.

마이그레이션 파일 위치:

```text
src/main/resources/db/migration/
```

### 파일명 규칙

```text
V{버전번호}__{변경내용}.sql
```

버전 번호와 설명 사이에는 언더바를 2개 사용합니다.

예시:

```text
V1__baseline_v2_1_2.sql
V2__patch_v2_1_2_to_v2_1_3.sql
V3__seed_demo_data.sql
V4__add_login_failure_count.sql
```

### 필수 규칙

1. 이미 `develop` 또는 `main`에 병합된 마이그레이션 파일은 수정하지 않습니다.
2. 변경이 필요하면 새로운 버전 파일을 추가합니다.
3. 새 버전 번호를 사용하기 전에 팀 채널에 공유합니다.
4. 하나의 버전 번호를 여러 사람이 동시에 사용하지 않습니다.
5. DDL 변경 PR에는 변경 목적과 영향 범위를 작성합니다.
6. 기존 데이터가 있는 테이블 변경 시 데이터 영향 여부를 작성합니다.
7. smoke test SQL은 `db/migration` 폴더에 넣지 않습니다.

### 잘못된 방식

이미 적용된 파일 수정:

```text
V2__patch_v2_1_2_to_v2_1_3.sql
```

### 올바른 방식

새 파일 추가:

```text
V3__add_contract_status_index.sql
```

### DB 초기화

로컬 개발 DB를 완전히 초기화해야 할 때만 사용합니다.

```bash
docker compose down -v
docker compose up -d
```

`-v` 옵션은 로컬 PostgreSQL 데이터 전체를 삭제하므로 필요한 데이터가 없는지 확인한 후 실행합니다.

---

## 11. 금액 계산 규칙

금액은 각 지급행 단위로 원 단위 반올림한 후 합산합니다.

```text
각 지급행 금액 계산
→ 원 단위 HALF_UP 반올림
→ 반올림된 금액 합산
```

합계 금액을 먼저 계산하고 마지막에 한 번만 반올림하지 않습니다.

수수료율, 지급률, 한도 및 정책값은 코드에 직접 작성하지 않고 정책 테이블에서 조회합니다.

잘못된 예시:

```java
BigDecimal rate = new BigDecimal("0.8");
```

정책값은 적용일, 보험사, 상품, 계약 및 우선순위를 기준으로 조회합니다.

---

## 12. 코드 작성 규칙

### 패키지 구조

도메인 중심 구조를 사용합니다.

```text
com.susukkang.fgc
├─ common
├─ auth
├─ base
├─ policy
├─ contract
├─ transaction
├─ schedule
├─ cap
├─ arbitrage
├─ ledger
├─ reconciliation
├─ exception_case
├─ validation
├─ audit
├─ dashboard
└─ batch
```

각 도메인 내부는 다음 구조를 기본으로 합니다.

```text
contract/
├─ controller/
├─ service/
├─ mapper/
└─ dto/
```

### Controller 구분

화면 Controller와 API Controller를 분리합니다.

```java
@Controller
```

* Thymeleaf 화면 반환
* URL 화면 라우팅 담당

```java
@RestController
```

* JSON API 응답
* `/api/v1` 경로 사용

### 명명 규칙

| 대상     | 규칙               | 예시                   |
| ------ | ---------------- | -------------------- |
| 클래스    | PascalCase       | `ContractService`    |
| 메서드    | camelCase        | `findContract`       |
| 변수     | camelCase        | `contractId`         |
| 상수     | UPPER_SNAKE_CASE | `MAX_LOGIN_FAILURE`  |
| DB 테이블 | snake_case       | `insurance_contract` |
| DB 컬럼  | snake_case       | `created_at`         |

---

## 13. 테스트 규칙

기능 구현 후 최소한 다음 항목을 확인합니다.

* 애플리케이션 빌드 성공
* 애플리케이션 실행 성공
* 정상 입력 처리
* 필수값 누락 처리
* 잘못된 입력 처리
* 중복 요청 처리
* 권한이 없는 사용자 접근 처리
* 기존 기능 영향 여부

Windows:

```bash
gradlew.bat clean test
gradlew.bat clean build
```

macOS 또는 Linux:

```bash
./gradlew clean test
./gradlew clean build
```

핵심 업무 로직은 가능한 경우 단위 테스트를 작성합니다.

```text
1200% 검증
차익거래 검증
계약 중복 검증
대사 금액 계산
복식부기 차변·대변 검증
배치 멱등성
```

---

## 14. 민감정보 관리

다음 정보는 Git에 커밋하지 않습니다.

```text
DB 비밀번호
API Key
Access Token
Refresh Token
개인정보
실제 고객정보
운영 서버 접속정보
```

다음 파일은 `.gitignore`에 포함합니다.

```text
.env
.env.*
*.env
application-local.yml
application-local.yaml
application-local.properties
application-secret.yml
application-secret.yaml
application-secret.properties
```

공유가 필요한 설정은 예제 파일로 작성합니다.

```text
.env.example
application-local.yml.example
```

예제 파일에는 실제 비밀번호를 작성하지 않습니다.

이미 민감정보를 push한 경우 단순히 파일을 삭제하는 것만으로는 충분하지 않습니다. 즉시 팀에 알리고 해당 키와 비밀번호를 폐기하거나 변경해야 합니다.

---

## 15. 로깅 규칙

개인정보와 민감정보를 로그에 출력하지 않습니다.

출력 금지 예시:

```text
비밀번호
주민등록번호
계좌번호 전체
Access Token
Refresh Token
DB 비밀번호
```

디버깅을 위해 작성한 다음 코드는 PR 전에 제거합니다.

```java
System.out.println();
printStackTrace();
```

Spring Logging을 사용합니다.

```java
@Slf4j
public class ContractServiceImpl {

    public void process() {
        log.info("계약 처리 시작");
    }
}
```

---

## 16. 문서 변경 규칙

요구사항명세서, 화면정의서, 인터페이스정의서, ERD 및 개발 가이드를 변경할 때는 다음 내용을 기록합니다.

* 변경 전 내용
* 변경 후 내용
* 변경 이유
* 영향을 받는 기능
* 영향을 받는 화면
* 영향을 받는 API
* 영향을 받는 테이블
* 문서 버전

문서 수정 브랜치 예시:

```text
docs/71-interface-definition-update
```

커밋 예시:

```text
docs(api): 계약 등록 인터페이스 정의 수정
```

---

## 17. 릴리즈 및 태그 규칙

발표 또는 제출 버전을 `main`에 병합한 후 태그를 생성합니다.

```bash
git checkout main
git pull origin main
```

태그 생성:

```bash
git tag -a v1.0.0-phase1 -m "FGC 1차 발표 버전"
```

원격 저장소에 태그 push:

```bash
git push origin v1.0.0-phase1
```

태그 목록 확인:

```bash
git tag
```

태그가 가리키는 코드 확인:

```bash
git show v1.0.0-phase1
```

이미 공유된 태그는 임의로 삭제하거나 같은 이름으로 다시 만들지 않습니다.

---

## 18. Pull Request 전 최종 확인

PR을 생성하기 전에 다음을 확인합니다.

* [ ] GitHub Issue가 생성되어 있습니다.
* [ ] 브랜치 이름에 이슈 번호가 포함되어 있습니다.
* [ ] 작업 브랜치가 최신 `develop`을 기준으로 합니다.
* [ ] 커밋 메시지 컨벤션을 준수했습니다.
* [ ] 로컬 빌드에 성공했습니다.
* [ ] 애플리케이션 실행을 확인했습니다.
* [ ] 관련 기능을 직접 테스트했습니다.
* [ ] 테스트 코드가 필요한 경우 작성했습니다.
* [ ] 기존 기능에 영향이 없는지 확인했습니다.
* [ ] 민감정보가 포함되지 않았습니다.
* [ ] 불필요한 로그와 디버깅 코드를 제거했습니다.
* [ ] 기존 Flyway 마이그레이션 파일을 수정하지 않았습니다.
* [ ] DB 변경이 있다면 새로운 마이그레이션 파일을 추가했습니다.
* [ ] 문서 변경이 필요한 경우 함께 반영했습니다.

---

## 19. 팀 협업 원칙

* 작업 시작 전 GitHub Issue를 생성하거나 담당 Issue를 확인합니다.
* 같은 파일이나 같은 기능을 동시에 수정하지 않도록 작업 내용을 공유합니다.
* DDL 변경 전에 Flyway 버전 번호를 공유합니다.
* 막힌 문제가 있으면 혼자 오래 보유하지 않고 팀에 공유합니다.
* 리뷰 의견은 코드 품질 개선을 위한 의견으로 받아들입니다.
* 기능 완료 여부보다 전체 프로젝트의 정상 동작을 우선합니다.
* 매일 스탠드업에서 다음 내용을 공유합니다.

```text
어제 완료한 작업
오늘 진행할 작업
현재 막힌 문제
다른 팀원과 충돌할 가능성이 있는 파일
```
