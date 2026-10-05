# 지급 JPA 전환 비교용 기준 구현

기준 커밋은 `d1a603df`다. 동일 JVM·DataSource·시드에서 현재 지급 서비스와 교대로 호출하기 위한 테스트 전용 구현이며, 운영 파일을 대체하지 않는다.

## 등록 및 호출

비교 테스트에서만 `@Import(PaymentBaselineConfiguration.class)`를 선언한다.

```java
@Autowired
@Qualifier("baselineCommissionPaymentService")
private CommissionPaymentService baseline;

@Autowired
@Qualifier("commissionPaymentServiceImpl")
private CommissionPaymentService current;
```

설정 클래스의 전체 이름은 `com.susukkang.fgc.transaction.performance.baseline.PaymentBaselineConfiguration`이다. 기준 지급·예외 서비스 Bean은 `defaultCandidate = false`이므로 기존 컨트롤러와 서비스의 무수식 주입은 현재 운영 구현을 선택한다. 기준 구현에는 `@Service` 및 `@Mapper`가 없다. XML은 운영의 `classpath:mapper/**/*.xml` 밖에 두고 테스트 설정의 독립 `SqlSessionTemplate`에서만 읽는다. 이 세션도 `SpringManagedTransactionFactory`와 같은 DataSource를 사용하므로 호출자의 실제 트랜잭션에 참여한다. 독립 SqlSessionFactory나 SqlSessionTemplate을 Spring Bean으로 노출하지 않는다.

## 복원 대상과 제한한 차이

다음 파일을 기준 커밋에서 복사했다.

- `transaction/service/CommissionPaymentServiceImpl.java`
- `transaction/mapper/CommissionPaymentMapper.java` 및 XML
- `cap/mapper/CapCheckMapper.java` 및 XML
- `cap/service/CapExceptionServiceImpl.java`
- `cap/mapper/CapExceptionMapper.java` 및 XML
- `cap/dto/CapExceptionStatusRow.java`

Java 경로는 `src/main/java/com/susukkang/fgc/` 기준이며 XML 경로는 `src/main/resources/mapper/` 기준이다.

변경은 테스트 패키지·타입명(`Baseline...`), 이에 따른 import/namespace/resultType, 자동 스캔용 `@Service`/`@Mapper` 제거, 출처 주석 추가뿐이다. 업무 메서드·SQL·`@Transactional`·`noRollbackFor`·`@PreAuthorize`는 보존했다. 기본 MyBatis 설정도 기존과 같은 `mapUnderscoreToCamelCase=true`, `localCacheScope=STATEMENT`를 사용한다. 기준 서비스의 예외 처리는 복원한 기준 CapExceptionService를 명시 주입하므로 현재 JPA 예외 저장 경로와 섞이지 않는다.

## 공통으로 사용하는 구현

비교 범위 밖의 다음 의존성은 두 서비스가 같은 현재 Bean을 사용한다.

- `AgentRepository` — 기준 커밋부터 이미 사용했다. AgentMapper를 새로 복원하지 않는다.
- `CapCalculator`, `CapValidator` — 한도 계산 및 판정.
- `CommissionPolicyService` — 정책 조회.
- `AuditLogService`, `FgcMessageResolver`, `ObjectMapper`.
- 지급 서비스 인터페이스, 요청·응답·명령 DTO, enum 및 `CommissionPaymentConfirmationRejectedException`.

따라서 이 비교는 애플리케이션 전체의 과거 빌드를 재현하는 실험이 아니라, 같은 공통 의존성 위에서 지급·한도 결과 저장 경로의 전환 전후를 비교하는 실험이다. 비교 시작 전 공통 의존성이 기준 커밋 이후 바뀌었는지 확인하고 그 차이를 측정 기록에 남긴다. `IDENTITY` 시퀀스 값과 DB 시각은 실행마다 달라지므로 응답 비교 시 업무 값과 연결 정합성을 검증해야 한다.

기준 파일 수정이 필요하다면 먼저 기준 커밋과의 변경 사유를 기록한다. 업무 로직이나 SQL을 임의로 정리하면 비교 기준이 달라진다.

## 재현 명령

Java 21과 Docker가 필요하다. 기존 `test` 프로필의 일회성 PostgreSQL을 사용한다. 두 명령은 동시에 실행하지 않는다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
./gradlew.bat --gradle-user-home .gradle-user --offline --no-daemon `
  -I scripts/verification/payment-performance.init.gradle `
  -PpaymentRun=round1 -PpaymentJfr=true `
  test --tests '*CommissionPaymentPersistencePerformanceTest' --console=plain

./gradlew.bat --gradle-user-home .gradle-user --offline --no-daemon `
  -I scripts/verification/payment-performance.init.gradle `
  -PpaymentRun=round2 -PpaymentJpaFirst=true -PpaymentJfr=true `
  test --tests '*CommissionPaymentPersistencePerformanceTest' --console=plain
```

각 실행은 새 테스트 JVM에서 1GB 힙을 사용한다. 계약 1건/2건 각각 40쌍을 워밍업하고 120쌍을 측정한다. `-PpaymentWarmups=40`, `-PpaymentSamples=120`으로 조절할 수 있다. 매 쌍의 구현 순서를 번갈아 바꾸고 두 번째 실행은 시작 순서도 반대로 한다. Hibernate 2차·쿼리 캐시와 양쪽 MyBatis 2차 캐시는 비활성화한다. 기존 Mapper XML에도 2차 캐시 선언이 없다.

CSV와 선택적 JFR 기록은 `build/reports/payment-performance/<paymentRun>.csv`, `.jfr`에 저장한다. 콘솔의 `PAYMENT_AB`는 nearest-rank p50/p95/p99, 최대값, SQL 수를 보여 준다. `PAYMENT_AB_PAIRED`는 각 쌍의 JPA−MyBatis 차이의 중앙값이다. 표본 수가 짝수이면 아래쪽 중앙값을 사용한다. 이 테스트는 init script 없이 실행하면 비활성화된다.

## 측정 범위와 읽는 법

- 별도 롤백 트랜잭션 안에서 등록 → 수정 → 사전검사 → 확정 → 같은 멱등키 재요청을 연속 호출한다. 트랜잭션 시작·커밋·롤백 및 HTTP 비용은 타이머 밖이며, 독립 HTTP 요청마다 새 영속성 컨텍스트를 만드는 부하 실험과는 다르다.
- 계약당 지급액은 10원이다. 계약 2건 시드는 CAP_WARNING 예외 1건도 저장하고 확정에 성공하는 경로다.
- 응답과 저장한 지급·귀속·판정·상세·감사 및 예외의 주요 업무 필드를 측정 밖에서 비교한다. 생성 ID·시각·감사 요청번호는 정규화하며, ID 연결·재요청 ID·미리보기 null ID는 별도로 검증한다. 숫자는 값의 동등성을 확인하므로 소수 자릿수 일치 검증은 아니다.
- 종료 시 업무 테이블의 행 수가 이전과 같은지 확인한다. 롤백은 시퀀스 증가나 DB/JVM 캐시까지 초기화하지 않는다.
- JDBC 관측은 prepare/execute/ResultSet/기타 드라이버 호출을 중복 없이 합산한다. 네트워크 대기·드라이버·일부 계측 비용도 포함하므로 DB 서버 실행시간이라고 부르지 않는다. 전체에서 이를 뺀 시간도 순수 ORM 비용이 아니다.
- CPU·GC·JIT 보조 관측 구간은 서비스 타이머보다 조금 넓다. GC/JIT는 JVM 전체 누적 계수의 차이이며 해당 호출 지연의 원인을 단독으로 입증하지 않는다. Windows CPU 수치가 15.625ms 단위로 나타나므로 0ms를 CPU 사용 없음으로 해석하지 않는다.
- 항목별 중앙값을 더해 총시간을 설명하지 않는다. 시간 분해에는 같은 표본의 값이나 평균을 사용한다. JFR 역시 측정 구간과 겹치는 이벤트를 살펴보고 시작 과정의 이벤트와 구분한다.

실제 수치와 해석은 Notion의 이슈 #373 문서에 기록한다. 이 파일은 비교 기준의 출처와 실행 방법을 보존한다.
