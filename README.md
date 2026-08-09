# 🚀 Fee GA Chu

<!--<img width="512" height="512" alt="Property 1=Logo" src="" />-->


> 보험 판매수수료 규제 대응 · 분급 · 환수 · 정산 검증 플랫폼

[![정합성 회귀 방지](https://img.shields.io/github/actions/workflow/status/feegachu/fgc/ci.yml?branch=develop&label=%EC%A0%95%ED%95%A9%EC%84%B1%20%ED%9A%8C%EA%B7%80%20%EB%B0%A9%EC%A7%80&logo=github)](https://github.com/feegachu/fgc/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

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

## 🔒 정합성 보증

수수료 계산은 틀리면 안 되는 코드입니다. 그래서 **정합성을 사람의 주의력이 아니라 CI로 강제**합니다.

모든 PR에서 실제 PostgreSQL 17(로컬 `docker-compose`와 동일 버전)을 띄워 통합테스트를 실행하며,
**하나라도 깨지면 머지가 차단**됩니다.

| 보장 | 근거 |
|---|---|
| H2 등 대체 DB 없이 실제 PostgreSQL로 검증 | `FGC-TER-004` |
| 로컬과 CI가 같은 DB 버전(`postgres:17`)을 사용 | `FGC-ECR-003` |
| `db/migration`·`db/demo` 간 Flyway 버전 중복 시 즉시 실패 | PR #30 재발 방지 |
| 보호 브랜치 + 리뷰 승인 1인 필수 | `FGC-ECR-004` · `FGC-QUR-004` |
| 의존성 취약점 주간 스캔(Dependabot) | `FGC-SER-010` |

깨진 테스트는 PR의 **"테스트 결과"** 체크에서 어느 테스트가 왜 실패했는지 바로 확인할 수 있습니다.

<br>

## 📆 프로젝트 기간
- 전체 기간: `2026.07.08 - 2026.11.17`
- 개발 기간: `2025.07.30 - 2026.11.17`

<br>

## 🤔 요구사항
For building and running the application you need:

[![Java](https://img.shields.io/badge/Java-21-red.svg)]()
[![SpringBoot](https://img.shields.io/badge/SpringBoot-3.5.16-green.svg)]()

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
<img src="https://img.shields.io/badge/Spring%20Batch-5FA04E?style=for-the-badge&logo=Spring&logoColor=white" />
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
