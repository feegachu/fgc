# 🚀 Fee GA Chu

<!--<img width="512" height="512" alt="Property 1=Logo" src="" />-->


> 보험 판매수수료 규제 대응 · 분급 · 환수 · 정산 검증 플랫폼


---

<br>

## 👥 멤버
| 이예소 | 손가영 | 강현준 | 강호원 | 박민준 |
|:------:|:------:|:------:|:------:|:------:|
| <img width="512" height="512" alt="lee" src="https://github.com/user-attachments/assets/9c8ed3e5-76ab-4b63-a5e4-2016ae823d29" /> | <img width="512" height="512" alt="son" src="https://github.com/user-attachments/assets/385f53a3-a8a8-4d58-8d2a-348eac301a60" /> | <img width="512" height="512" alt="kangh" src="https://github.com/user-attachments/assets/687927df-7a03-41c2-a717-7f7845d09ee7" /> | <img width="512" height="512" alt="kanghh" src="https://github.com/user-attachments/assets/6b284df9-96e4-4e76-8a47-6d962dee263c" /> | <img width="512" height="512" alt="p" src="https://github.com/user-attachments/assets/24721b7b-4d14-4b1f-860b-1eacb9de82c5" /> |
| PM / BA | DB/정산엔진 Lead | Tech Lead/Backend Lead | DevOps/QA Lead | Frontend/UX Lead |
| [GitHub](https://github.com/yslee4601) | [GitHub](https://github.com/gayo73) | [GitHub](https://github.com/HyunJuneKang) | [GitHub](https://github.com/C4t4ddict) | [GitHub](https://github.com/ParkMinjun0721) |

<br>


## 📱 소개

> **FeeGaChu(FGC)**는 GA 및 보험영업 조직이 직면한 **'3중 데드라인(1200%룰 확대, 수수료 비교공시, 7년 분급 시행)'** 규제에 대응하기 위한 **정산 검증 사이드카 플랫폼**입니다.

> 기존 엑셀이나 노후 전산망으로는 감당하기 불가능한 수백만 건의 분급 스케줄을 처리하고 설계사별 한도를 실시간으로 합산·검증합니다. 이를 통해 수수료 정산의 인건비를 절감하고, 환수 누락액 및 규제 위반 리스크를 원천 차단합니다.


<br>

## 📆 프로젝트 기간
- 전체 기간: `2026.07.08 - 2026.11.17`
- 개발 기간: `2025.07.30 - 2026.11.17`

<br>

## 🤔 요구사항
For building and running the application you need:

[![Java](https://img.shields.io/badge/Java-21-red.svg)]()
[![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.0-green.svg)]()

<br>

## ⚒️ 개발 환경
* BackEnd : IntelliJ
* 버전 및 이슈 관리 : Github, Github Issues, Jira
* 협업 툴 : Slack, Notion

<br>

## 아키텍처 다이어그램
*(아키텍처 이미지 추가 예정)*

- **Modular Monolith** 구조 채택
- 정책 버전 관리, 배치 멱등성 보장, 감사 추적(Audit Log) 원칙 적용

<br>

## 🔎 기술 스택
### Envrionment
<div align="left">
<img src="https://img.shields.io/badge/AWS-FF9900?style=for-the-badge&logo=AmazonAWS&logoColor=white" />
<img src="https://img.shields.io/badge/Linux-FCC624?style=for-the-badge&logo=Linux&logoColor=black" /> 
<img src="https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=Docker&logoColor=white" /> 
<img src="https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=GitHub&logoColor=white" />
</div>

### Development - Backend
<div align="left">
<img src="https://img.shields.io/badge/Java-007396?style=for-the-badge&logo=java&logoColor=white" />
<img src="https://img.shields.io/badge/Spring%20Boot-5FA04E?style=for-the-badge&logo=SpringBoot&logoColor=white" />
<img src="https://img.shields.io/badge/Spring%20Batch-5FA04E?style=for-the-badge&logo=Spring&logoColor=white" 
<img src="https://img.shields.io/badge/JPA-D70F64?style=for-the-badge&logo=jakarta&logoColor=white" /> 
<img src="https://img.shields.io/badge/MyBatis-000000?style=for-the-badge&logo=MyBatis&logoColor=white" />
<img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" />
</div>

### Development - Frontend
<div align="left">
<img src="https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=React&logoColor=black" />
<img src="https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=TypeScript&logoColor=white" />
<img src="https://img.shields.io/badge/Thymeleaf-005F0F?style=for-the-badge&logo=Thymeleaf&logoColor=white" />
</div>


<br>

## 📱 화면 구성
<table>
  <tr>
    <td>
      사진 넣어주세요
    </td>
    <td>
      사진 넣어주세요
    </td>

  </tr>
</table>

## 🔖 브랜치 컨벤션
| 브랜치 종류   | Prefix     | 용도                                                 | 예시                              |
| -------- | ---------- | -------------------------------------------------- | ------------------------------- |
| 운영(메인)   | `main`     | 실제 서비스가 동작하는 배포용 브랜치                               | `main`                          |
| 개발 통합    | `develop`  | 다음 릴리스 준비를 위한 개발 통합 브랜치                            | `develop`                       |
| 기능 추가    | `feat/`    | 새로운 기능(Feature) 개발용 브랜치                            | `feat/user-auth`                |
| 버그 수정    | `bugfix/`  | develop 브랜치상의 일반 버그 수정                             | `bugfix/login-nullpointer`      |
| 긴급 수정    | `hotfix/`  | 운영 중인 main 브랜치에서 발생한 긴급 버그 수정                      | `hotfix/payment-timeout`        |
| 리팩터링     | `refactor/`   | 기존 코드 구조 개선·리팩터링용 브랜치                              | `refactor/order-service`           |
| 릴리스 준비   | `release/` | 배포 전 최종 버전 준비·테스트용 브랜치                             | `release/v1.2.0`                |
| 빌드·설정    | `chore/`   | 패키지/의존성 업데이트, 빌드 설정, 환경 구성 등 비기능 작업                | `chore/update-dependencies`     |
| 문서       | `docs/`    | API 명세서·README·아키텍처 다이어그램 등 문서 작업                  | `docs/openapi-spec`             |
| CI/CD 설정 | `ci/`      | GitHub Actions·Jenkins·Dockerfile 등 CI·CD 파이프라인 설정 | `ci/github-actions-pipeline`    |



<br>

## 📁 PR 컨벤션
* PR 시, 템플릿이 등장한다. 해당 템플릿에서 작성해야할 부분은 아래와 같다
    1. `PR 제목 작성`, PR 제목 규칙에 맞게 작성
    2. `작업 내용 작성`, 작업 내용에 대해 자세하게 작성
    3. `관련 이슈 작성`, PR과 관련된 이슈를 연결
    4. `리뷰 포인트`, 본인 PR에서 꼭 확인해야 할 부분을 작성
    6. `PR 올리기 전 확인`, PR 올리기 전 확인사항 체크

#### 🌟 태그 종류 (커밋 컨벤션과 동일)
| 태그        | 설명                                                   |
|-------------|--------------------------------------------------------|
| [Feat]      | 새로운 기능 추가                                       |
| [Fix]       | 버그 수정                                              |
| [Refactor]  | 코드 리팩토링 (기능 변경 없이 구조 개선)              |
| [Style]     | 코드 포맷팅, 들여쓰기 수정 등                         |
| [Docs]      | 문서 관련 수정                                         |
| [Test]      | 테스트 코드 추가 또는 수정                            |
| [Chore]     | 빌드/설정 관련 작업                                    |
| [Design]    | UI 디자인 수정                                         |
| [Hotfix]    | 운영 중 긴급 수정                                      |
| [CI/CD]     | 배포 및 워크플로우 관련 작업                          |

### ✅ PR 예시 모음
> [Chore] 프로젝트 초기 세팅 <br>
> [Feat] 프로필 화면 UI 구현 <br>
> [Fix] iOS 17에서 버튼 클릭 오류 수정 <br>
> [Design] 로그인 화면 레이아웃 조정 <br>
> [Docs] README에 프로젝트 소개 추가 <br>

<br>

## 💡 이슈 컨벤션
* 이슈 작성 시, 템플릿 선택 창 등장
    1. 작성할 이슈에 맞게 템플릿 선택
    2. 이슈 템플릿에 맞게 작성 후 이슈 작성
    3. 기능/버그 외의 이슈는 Cutom(기본) 이슈 템플릿 선택

## 📑 커밋 컨벤션

### 🏷️ 커밋 태그 가이드

| 태그        | 설명                                                   |
|-------------|--------------------------------------------------------|
| [Feat]      | 새로운 기능 추가                                       |
| [Fix]       | 버그 수정                                              |
| [Refactor]  | 코드 리팩토링 (기능 변경 없이 구조 개선)              |
| [Style]     | 코드 포맷팅, 세미콜론 누락, 들여쓰기 수정 등          |
| [Docs]      | README, 문서 수정                                     |
| [Test]      | 테스트 코드 추가 및 수정                              |
| [Chore]     | 패키지 매니저 설정, 빌드 설정 등 기타 작업           |
| [Design]    | UI, CSS, 레이아웃 등 디자인 관련 수정                |
| [Hotfix]    | 운영 중 긴급 수정이 필요한 버그 대응                 |
| [CI/CD]     | 배포 관련 설정, 워크플로우 구성 등                    |

### ✅ 커밋 예시 모음
> [Chore] 프로젝트 초기 세팅 <br>
> [Feat] 프로필 화면 UI 구현 <br>
> [Fix] iOS 17에서 버튼 클릭 오류 수정 <br>
> [Design] 로그인 화면 레이아웃 조정 <br>
> [Docs] README에 프로젝트 소개 추가 <br>

<br>

## 🗂️ 폴더 컨벤션
- 도메인형 구조

![image](https://github.com/user-attachments/assets/0b566fe6-708e-48be-b8d2-afabbf060bf3)


# FGC — GA 수수료 정산·검증 플랫폼

## 고정 버전 (임의로 올리지 마세요)
- Java 21
- Spring Boot 3.x.y  ← 2026-08-03 start.spring.io 생성 기준
- PostgreSQL 17 (docker-compose)
- Gradle Wrapper 사용 (`./gradlew`, 로컬 Gradle 설치 불필요)

## 5분 만에 띄우기
1. Docker Desktop 실행
2. `docker compose up -d`
3. `./gradlew bootRun`     (Windows: `gradlew.bat bootRun`)
4. http://localhost:8080 접속
5. Swagger: http://localhost:8080/swagger-ui.html

## DB 초기화 (뭔가 꼬였을 때)
docker compose down -v && docker compose up -d && ./gradlew bootRun

## 문서
- 화면정의서 v2.0 / 화면 목업: `FGC_화면_MVP/`
- API·배치 계약: `docs/05_인터페이스정의서_v2_0.md`
- 규제 근거: `docs/07_규제조문표_v0.2.1.md`
- 컨벤션: `docs/컨벤션/`
test
