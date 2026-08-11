package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.code.PaymentStage;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IF-BAT-01 capCheckStep의 "partition 2개(INSURER_TO_GA / GA_TO_FC)" 요구사항
 */
public class PaymentStagePartitioner implements Partitioner {

    public static final String PAYMENT_STAGE_KEY = "paymentStage";

    /**
     * Spring Batch가 capCheckStep을 실행하기 직전에 이 메서드를 한 번 불러 "몇 개의 파티션을,
     * 각각 어떤 데이터로 실행할지"를 물어봄
     */
    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (PaymentStage stage : PaymentStage.values()) {
            ExecutionContext context = new ExecutionContext();
            context.putString(PAYMENT_STAGE_KEY, stage.name());
            partitions.put(stage.name(), context);
        }
        return partitions;
    }
}
