package com.susukkang.fgc.common.persistence.baseline;

/** 검증용으로 동결한 SQL만 등록한다. XML 해석이나 런타임 Mapper 스캔은 없다. */
final class BaselineStatements {
    static JdbcBaseline.Statement lookup(String name, Object input) {
        int dot = name.lastIndexOf('.');
        String method = name.substring(dot + 1);
        return switch (name.substring(0, dot)) {
            case "com.susukkang.fgc.arbitrage.baseline.ArbitrageJdbcBaseline" -> ArbitrageBaselineSql.statement(method, input);
            case "exceptionCaseBaseline" -> ExceptionCaseBaselineSql.statement(method, input);
            case "com.susukkang.fgc.cap.performance.baseline.CapContractJdbcBaseline" -> CapContractBaselineSql.statement(method, input);
            case "com.susukkang.fgc.cap.performance.baseline.CapIncludedAmountJdbcBaseline" -> CapIncludedAmountBaselineSql.statement(method, input);
            case "com.susukkang.fgc.cap.performance.baseline.CapRuleJdbcBaseline" -> CapRuleBaselineSql.statement(method, input);
            case "com.susukkang.fgc.cap.performance.baseline.CapScheduleAmountJdbcBaseline" -> CapScheduleAmountBaselineSql.statement(method, input);
            case "com.susukkang.fgc.cap.performance.baseline.RefundRateJdbcBaseline" -> RefundRateBaselineSql.statement(method, input);
            case "contractBaseline" -> ContractReadBaselineSql.statement(method, input);
            case "correction-action-baseline" -> CorrectionActionBaselineSql.statement(method, input);
            case "journalBaseline.JournalDetail" -> JournalDetailBaselineSql.statement(method, input);
            case "journalBaseline.JournalImbalance" -> JournalImbalanceBaselineSql.statement(method, input);
            case "journalBaseline.JournalSearch" -> JournalSearchBaselineSql.statement(method, input);
            case "journalValidationBaseline" -> ValidationRunReadBaselineSql.statement(method, input);
            case "journalBaseline.JournalAccount" -> JournalAccountBaselineSql.statement(method, input);
            case "journalBaseline.Journal" -> JournalWriteBaselineSql.statement(method, input);
            case "com.susukkang.fgc.transaction.performance.baseline.PaymentCapCheckJdbcBaseline" -> PaymentCapCheckBaselineSql.statement(method, input);
            case "com.susukkang.fgc.transaction.performance.baseline.PaymentCapExceptionJdbcBaseline" -> PaymentCapExceptionBaselineSql.statement(method, input);
            case "com.susukkang.fgc.transaction.performance.baseline.CommissionPaymentJdbcBaseline" -> CommissionPaymentBaselineSql.statement(method, input);
            case "reconciliationBaseline.GaFcReconciliation" -> GaFcReconciliationBaselineSql.statement(method, input);
            case "reconciliationBaseline.InsurerGaReconciliation" -> InsurerGaReconciliationBaselineSql.statement(method, input);
            case "reconciliationBaseline.ReconciliationResult" -> ReconciliationResultBaselineSql.statement(method, input);
            case "reconciliationBaseline.ReconciliationRunHistory" -> ReconciliationRunHistoryBaselineSql.statement(method, input);
            case "reconciliationBaseline.ReconciliationRun" -> ReconciliationRunBaselineSql.statement(method, input);
            case "com.susukkang.fgc.schedule.repository.ScheduleJdbcBaseline" -> ScheduleBaselineSql.statement(method, input);
            default -> throw new IllegalArgumentException("Unknown baseline: " + name);
        };
    }
}
