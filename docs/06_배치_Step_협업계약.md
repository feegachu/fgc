# MonthlyValidationJob Step 협업 계약

> 기준: develop `fe6d0e6c` (2026-10-22). 배선은 `validation/batch/MonthlyValidationJobConfig.java`, 포트는 `validation/batch/contract/`. 2차 변경 예정은 맨 아래 절.

## 공통 규칙

- 현재 Step 1은 JobParameter에서 `CreateValidationRunCommand`를 만들어 서비스를 호출하고, 반환된 `validation_run_id`를 Job ExecutionContext에 넣는다. Step 2는 그 ID와 `validationMonth`로 선별 서비스를 직접 호출한다. Step 3~8의 포트가 `ValidationStepContext`를 받는다. `ValidationJobContext`는 현재 미연결된 생성 포트의 설계 입력이므로 실제 Step 1 입력과 구분한다(SRC-064).
- 모든 포트 구현체는 `requestId`, `triggeredBy`를 감사 로그·업무 저장에 그대로 전달한다.
- Writer는 UPSERT 또는 `ON CONFLICT DO NOTHING`을 사용한다. 기존 결과를 삭제하고 다시 쓰지 않는다.
- 치명적 오류는 예외로 전파해 Spring Batch Step을 실패시킨다. 허용된 오류는 계약 식별자를 담은 `StepProcessingResult.skips`로 반환하지만, 오류의 격리 단위와 Tasklet의 결과 소비 범위는 Step별로 다르다. 특히 Step 7은 아래 제한을 따른다(SRC-065).
- skip 한도는 Step 3·4에서 **100건 고정**이다(`ScheduleCheckTasklet`·`CapCheckTasklet`의 `DEFAULT_SKIP_LIMIT`, 설정값 아님). 넘으면 `SkipLimitExceededException`으로 Step을 실패시킨다.
- Step 9(담당자 검토)와 Step 10(확정)은 사람이 수행하므로 이 계약에 포함하지 않는다.
- Tasklet이 부르는 대상: Step 1·2는 서비스를 직접 부른다(`CreateRunTasklet` → `ValidationRunCreateService`, `SelectTargetTasklet` → `ValidationTargetSelectionService`). `ValidationRunCreationPort`·`TargetSelectionPort`는 인터페이스만 있고 구현체가 없다. Step 3~8은 아래 포트의 구현체를 부른다. `MonthlyValidationStepCoordinator`는 skip 한도·불균형 판단을 묶은 협업 규칙 클래스이지만 Job에 연결돼 있지 않고 테스트(`MonthlyValidationStepCoordinatorTest`)에서만 쓴다 — Tasklet이 같은 판단을 직접 한다.

| Step | 호출 대상과 입력 | 결과·저장 대상 | 멱등성 키 | 오류 정책 |
| --- | --- | --- | --- | --- |
| 1 실행 생성 | `ValidationRunCreateService` (`CreateRunTasklet`) | `validation_run` | `uq_validation_run (validation_month, run_no)` | 생성 불가 오류는 Job 실패 |
| 2 대상 선별 | `ValidationTargetSelectionService` (`SelectTargetTasklet`) | `validation_target` 및 선별 사유·상품/환급률표 스냅샷 | `uq_validation_target (validation_run_id, contract_id)` | 치명 오류는 Step 실패 |
| 3 스케줄 재생성 | `ScheduleRegenerationPort.regenerateSchedules(ValidationStepContext)` — 구현 `ValidationRunScheduleService` | `StepProcessingResult`, `schedule_header`, `schedule_line` | 활성 OPERATIONAL 및 `uq_schedule_line_business` | 계약 오류는 `DATA_QUALITY` 예외 생성 후 skip(한도 100건) |
| 4 1,200% 검증 | `CapCheckBatchPort.check(context, paymentStage)` — 구현 `CapCheckBatchAdapter`, `PaymentStagePartitioner`로 지급단계별 워커 Step | `StepProcessingResult`, `cap_check`, `cap_check_detail` | `uq_cap_check_monthly (validation_run_id, contract_id, payment_stage)` | 지급단계별 처리. skip 100건 초과 시 Step 실패 |
| 5 차익거래 | `ArbitrageCheckBatchPort.check(ValidationStepContext)` — 구현 `ArbitrageCheckBatchAdapter` | `StepProcessingResult`, `arbitrage_check` | `uq_arbitrage_check (validation_run_id, contract_id, payment_stage, as_of_date)` | 개별 대상 skip 가능 |
| 6a 원장 기표 | `JournalPostingPort.post(ValidationStepContext)` — 구현 `JournalPostingPortImpl` | `JournalPostingResult`, 검증 원장 | 원장 원천 업무키 | 기표 오류는 Step 실패 |
| 6b 균형검사 | `LedgerImbalanceCheckPort.check(ValidationStepContext)` — 구현 `LedgerImbalanceCheckPortImpl` | `LedgerImbalanceResult`, 불균형 검증 결과 | 원장 원천 업무키 | `imbalanceCount > 0`이면 즉시 Step 실패(`LedgerImbalanceDetectedException`) |
| 7 양방향 대사 | `ReconciliationBatchPort.reconcile(context, paymentStage)` — 구현 `ReconciliationBatchAdapter` | `StepProcessingResult`, `reconciliation_run/result/match` | `uq_reconciliation_result (reconciliation_run_id, match_group_key)` | Tasklet이 두 지급단계를 순차 호출한다. 어댑터의 허용 오류는 실패 요청 보험사의 선별 계약 전체를 skip 목록에 넣을 수 있다. 현재 Tasklet은 반환값을 사용하지 않아 통계·skip 예외 전달은 미구현. 치명적 예외는 전파(SRC-065) |
| 8 예외 생성 | `ExceptionGenerationPort.generate(ValidationStepContext)` — 구현 `ValidationRunExceptionServiceImpl` | `StepProcessingResult`, `exception_case`, `exception_occurrence`, `exception_action` | `uq_exception_key (exception_key)` · 검출 이력은 `(exception_case_id, validation_run_id)` | 중복은 생성하지 않고 같은 업무건에 검출 이력만 더한다(SRC-032) |

### Step 7의 현재 skip 처리 제한 (SRC-065)

`ReconciliationTasklet`은 두 `StepProcessingResult`를 누적하지 않으며 `StepContribution` 처리·skip 건수를 갱신하지 않는다. `ContractSkip` 목록도 Step 8에 전달하지 않는다. Step 8은 저장된 검증 결과에서 예외를 만들므로, 대사 실행이 실패해 결과가 없는 계약의 skip 사유까지 자동으로 예외함에 보존된다고 보장할 수 없다. Step 완료나 skip 통계 0만으로 대사 성공을 판단하지 않고 `reconciliation_run` 실패 여부도 확인한다.

후속 S40에서 두 지급단계 결과 누적·통계 반영·계약 ID와 사유를 보존한 예외 생성 연결을 구현하고 재실행 중복을 검증한다. 그때까지 위 내용을 구현 완료로 표시하지 않는다. 치명적 예외의 기존 전파 동작은 유지한다.

## 상태·감사 계약

- Step 1 성공 직후 `validation_run`은 `CREATED → RUNNING`, `current_step = 1`로 전이한다.
- Step 2~8이 성공하면 `current_step`을 해당 업무 단계로 갱신한다. Step 6a·6b는 모두 `current_step = 6`을 사용한다(`ValidationRunStepProgressListener`).
- Step 실패는 실패 단계와 사유를 `validation_run`에 기록한다. 진행상황 기록 중 예외가 나면 Step·Job을 FAILED로 올린다.
- 자동 재시작은 제공하지 않는다. `FAILED` 실행은 재기동하지 않고 새 실행을 만든다(`ValidationRunExecuteServiceImpl`) — 실패 지점 재시작은 2차 FUN-045.
- 시작·완료·실패 감사 로그의 `user_id`는 `triggeredBy`, `request_id`는 JobParameter `requestId`다(`ValidationRunBatchAuditServiceImpl`).

## 2차 변경 예정 (요구사항 v2.2.2-r19)

이 절은 아직 구현되지 않은 계획이다. 구현하면 위 표를 고친다.

- **FUN-045 실패 지점 재시작**: `FAILED → RUNNING` 재기동(IF-API-48), Spring Batch 재시작으로 실패한 Step부터 다시 실행하고 업무 건이 정확히 한 번 반영됨을 증명한다. 위 멱등성 키가 그 전제다.
- **FUN-062·064 계약체결비용·유지관리 검증**: `acquisition_cost_check`·`maintenance_check`를 채우는 Step을 추가한다(테이블은 있음). 추가할 때 `current_step` 번호 체계(0~10)와 확정 체크리스트(운영정책서 제44조)를 함께 갱신한다.
- **FUN-068 보험사 원천 명세(AI 문서 입력분)**: 새 Step이 아니다. 적재된 `INSURER_STATEMENT` 원천은 Step 6a·7이 기존 조건(`INSURER_COMMISSION`·`PAYMENT`·`CONFIRMED`·`CONTRACT` 귀속)으로 읽는다. 차감·조정·환수 명세는 이 Job의 기표·대사 대상이 아니다(운영정책서 제55조).
