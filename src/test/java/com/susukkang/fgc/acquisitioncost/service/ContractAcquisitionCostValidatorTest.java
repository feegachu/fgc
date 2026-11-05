package com.susukkang.fgc.acquisitioncost.service;

import com.susukkang.fgc.acquisitioncost.service.ContractAcquisitionCostValidator.Input;
import com.susukkang.fgc.acquisitioncost.service.ContractAcquisitionCostValidator.Line;
import com.susukkang.fgc.acquisitioncost.service.ContractAcquisitionCostValidator.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus.NORMAL;
import static com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus.REVIEW_REQUIRED;
import static com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus.VIOLATION;
import static org.assertj.core.api.Assertions.assertThat;

/** FUN-062 판정표. 기준액 3,000,000원과 한도율 100%는 운영정책서 제46조 검산 예시의 가정값이며 규정 수치가 아니다. */
class ContractAcquisitionCostValidatorTest {
    private static final BigDecimal BASE = new BigDecimal("3000000");
    private static final BigDecimal LIMIT_PCT = new BigDecimal("100.000000");
    private long lineId;

    private Line upfront(int installment, long amount) {
        return line(installment, "BASE_COMMISSION", "UPFRONT", amount, null);
    }

    private Line maintenance(int installment, long amount) {
        return line(installment, "MAINTENANCE_COMMISSION", "MAINTENANCE", amount, null);
    }

    private Line line(int installment, String item, String component, long amount, String evidence) {
        return new Line(++lineId, installment, item, item + " 이름", component, BigDecimal.valueOf(amount), evidence);
    }

    private static Result validate(Line... lines) {
        return ContractAcquisitionCostValidator.validate(new Input(BASE, LIMIT_PCT, LIMIT_PCT, List.of(lines)));
    }

    @Test
    void goldenFourYearSampleKeepsEachUsageSeparate() {
        List<Line> lines = new ArrayList<>(List.of(upfront(1, 600_000)));
        for (int i = 1; i <= 48; i++) lines.add(maintenance(i, 45_000));

        Result result = ContractAcquisitionCostValidator.validate(new Input(BASE, LIMIT_PCT, LIMIT_PCT, lines));

        assertThat(result.status()).isEqualTo(NORMAL);
        assertThat(result.upfront().calculatedAmount()).isEqualByComparingTo("600000");
        assertThat(result.upfront().limitAmount()).isEqualByComparingTo("3000000");
        assertThat(result.upfront().usagePct()).isEqualByComparingTo("20");
        assertThat(result.maintenance().calculatedAmount()).isEqualByComparingTo("2160000");
        assertThat(result.maintenance().usagePct()).isEqualByComparingTo("72");
        assertThat(result.indirectSupportAmount()).isEqualByComparingTo("0");
    }

    @Test
    void equalToLimitIsNormalAndOneWonOverIsViolationFromTheCrossingInstallment() {
        Result equal = validate(upfront(1, 2_000_000), upfront(2, 1_000_000));
        assertThat(equal.upfront().status()).isEqualTo(NORMAL);
        assertThat(equal.upfront().usagePct()).isEqualByComparingTo("100");
        assertThat(equal.upfront().exceededFromInstallmentNo()).isNull();

        Result over = validate(upfront(1, 2_000_000), upfront(2, 1_000_000), upfront(3, 1));
        assertThat(over.status()).isEqualTo(VIOLATION);
        assertThat(over.upfront().status()).isEqualTo(VIOLATION);
        assertThat(over.upfront().exceededFromInstallmentNo()).isEqualTo(3);
        assertThat(over.upfront().reason()).contains("1원 초과", "3회차부터");
        assertThat(over.reasons()).extracting(r -> r.code()).containsExactly("LIMIT_EXCEEDED");
    }

    @Test
    void limitsAreNotASharedWalletOneSideDoesNotReduceTheOther() {
        // 합계는 기준액의 2배지만 각자 기준액 이내라 정상이다(운영정책서 제45조의2). 차감식이면 위반으로 나온다.
        Result both = validate(upfront(1, 3_000_000), maintenance(1, 3_000_000));
        assertThat(both.status()).isEqualTo(NORMAL);
        assertThat(both.upfront().limitAmount()).isEqualByComparingTo("3000000");
        assertThat(both.maintenance().limitAmount()).isEqualByComparingTo("3000000");

        Result one = validate(upfront(1, 3_000_001), maintenance(1, 100));
        assertThat(one.status()).isEqualTo(VIOLATION);
        assertThat(one.upfront().status()).isEqualTo(VIOLATION);
        assertThat(one.maintenance().status()).isEqualTo(NORMAL);
    }

    @Test
    void indirectSupportIsSeparatedButCountsTowardTheMaintenanceTotal() {
        Result over = validate(maintenance(1, 2_500_000), line(1, "COMMON_COST", "MAINTENANCE", 500_001, null));
        assertThat(over.indirectSupportAmount()).isEqualByComparingTo("500001");
        assertThat(over.maintenance().calculatedAmount()).isEqualByComparingTo("3000001");
        assertThat(over.maintenance().status()).isEqualTo(VIOLATION);
    }

    @Test
    void noFixedNineteenPercentLimitAppliesToCommonCost() {
        // 공통비가 기준액의 30%여도 유지관리 총액이 한도 이내면 정상이다. 19% 고정 한도가 있으면 여기서 깨진다(REG-06).
        Result result = validate(maintenance(1, 2_000_000), line(1, "COMMON_COST", "COMMON_COST", 900_000, null));
        assertThat(result.indirectSupportAmount()).isEqualByComparingTo("900000");
        assertThat(result.maintenance().calculatedAmount()).isEqualByComparingTo("2900000");
        assertThat(result.status()).isEqualTo(NORMAL);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void longTermMaintenanceCountsTowardTheMaintenanceTotal() {
        Result result = validate(maintenance(1, 2_016_000), line(61, "LONG_TERM_MAINTENANCE", "LONG_TERM_MAINTENANCE", 288_000, null));
        assertThat(result.maintenance().calculatedAmount()).isEqualByComparingTo("2304000");
        assertThat(result.maintenance().usagePct()).isEqualByComparingTo("76.8");
    }

    @Test
    void missingDataIsNullWhileAnEmptyBucketIsARealZero() {
        Result noBase = ContractAcquisitionCostValidator.validate(new Input(null, LIMIT_PCT, LIMIT_PCT, List.of(upfront(1, 100))));
        assertThat(noBase.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(noBase.upfront().limitAmount()).isNull();
        assertThat(noBase.upfront().usagePct()).isNull();
        assertThat(noBase.upfront().calculatedAmount()).isEqualByComparingTo("100");

        Result noSchedule = ContractAcquisitionCostValidator.validate(new Input(BASE, LIMIT_PCT, LIMIT_PCT, null));
        assertThat(noSchedule.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(noSchedule.upfront().calculatedAmount()).isNull();
        assertThat(noSchedule.maintenance().calculatedAmount()).isNull();
        assertThat(noSchedule.indirectSupportAmount()).isNull();
        assertThat(noSchedule.upfront().limitAmount()).isEqualByComparingTo("3000000");

        Result noLimitRate = ContractAcquisitionCostValidator.validate(new Input(BASE, LIMIT_PCT, null, List.of()));
        assertThat(noLimitRate.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(noLimitRate.upfront().status()).isEqualTo(NORMAL);
        assertThat(noLimitRate.maintenance().status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(noLimitRate.reasons()).extracting(r -> r.code()).containsExactly("LIMIT_RATE_MISSING");

        Result maintenanceOnly = validate(maintenance(1, 100));
        assertThat(maintenanceOnly.upfront().calculatedAmount()).isEqualByComparingTo("0");
        assertThat(maintenanceOnly.upfront().status()).isEqualTo(NORMAL);
    }

    @Test
    void exclusionNeedsEvidenceOtherwiseItIsCountedAndReviewed() {
        Result proven = validate(upfront(1, 3_000_000), line(1, "NEWCOMER_SUPPORT", "NEWCOMER_SUPPORT", 300_000, "EVD-1"));
        assertThat(proven.status()).isEqualTo(NORMAL);
        assertThat(proven.upfront().calculatedAmount()).isEqualByComparingTo("3000000");
        assertThat(proven.exclusions()).singleElement().satisfies(line -> {
            assertThat(line.excluded()).isTrue();
            assertThat(line.evidenceRef()).isEqualTo("EVD-1");
        });

        Result unproven = validate(upfront(1, 1_000_000), line(1, "NEWCOMER_SUPPORT", "NEWCOMER_SUPPORT", 300_000, null));
        assertThat(unproven.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(unproven.upfront().calculatedAmount()).isEqualByComparingTo("1300000");
        assertThat(unproven.exclusions()).singleElement().satisfies(line -> assertThat(line.excluded()).isFalse());
        assertThat(unproven.reasons()).extracting(r -> r.code()).containsExactly("EXCLUSION_EVIDENCE_MISSING");

        // 증빙 없는 금액을 넣어 한도를 넘으면 위반이 우선한다.
        Result overByUnproven = validate(upfront(1, 2_900_000), line(2, "NEWCOMER_SUPPORT", "NEWCOMER_SUPPORT", 300_000, null));
        assertThat(overByUnproven.status()).isEqualTo(VIOLATION);
        assertThat(overByUnproven.upfront().exceededFromInstallmentNo()).isEqualTo(2);
    }

    @Test
    void unknownComponentsStayOutOfTheTotalsAndForceReview() {
        Result result = validate(upfront(1, 100), line(1, "INCENTIVE", "INCENTIVE", 70, null), line(2, "OTHER", null, 30, null));
        assertThat(result.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.unclassifiedAmount()).isEqualByComparingTo("100");
        assertThat(result.upfront().calculatedAmount()).isEqualByComparingTo("100");
        assertThat(result.upfront().status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.maintenance().status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(result.lineGroups()).extracting(g -> g.bucket()).containsExactly("UPFRONT", "UNCLASSIFIED", "UNCLASSIFIED");
    }

    @Test
    void amountsRoundLineByLineAndHugeUsageIsLeftEmpty() {
        Result rounded = ContractAcquisitionCostValidator.validate(new Input(BASE, LIMIT_PCT, LIMIT_PCT, List.of(
                new Line(1L, 1, "BASE_COMMISSION", "n", "UPFRONT", new BigDecimal("100.40"), null),
                new Line(2L, 2, "BASE_COMMISSION", "n", "UPFRONT", new BigDecimal("100.50"), null))));
        assertThat(rounded.upfront().calculatedAmount()).isEqualByComparingTo("201");

        Result huge = ContractAcquisitionCostValidator.validate(new Input(BigDecimal.ONE, LIMIT_PCT, LIMIT_PCT,
                List.of(new Line(1L, 1, "BASE_COMMISSION", "n", "UPFRONT", new BigDecimal("9000000000"), null))));
        assertThat(huge.upfront().status()).isEqualTo(VIOLATION);
        assertThat(huge.upfront().usagePct()).isNull();

        Result zeroLimit = ContractAcquisitionCostValidator.validate(new Input(BASE, BigDecimal.ZERO, LIMIT_PCT, List.of(upfront(1, 1))));
        assertThat(zeroLimit.upfront().status()).isEqualTo(VIOLATION);
        assertThat(zeroLimit.upfront().usagePct()).isNull();
    }
}
