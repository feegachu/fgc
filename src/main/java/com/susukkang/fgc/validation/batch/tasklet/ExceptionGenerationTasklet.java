package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.contract.ExceptionGenerationPort;
import com.susukkang.fgc.validation.service.ValidationRunExceptionServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 설명 : ExceptionGenerationTasklet
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-14
 */
@RequiredArgsConstructor
public class ExceptionGenerationTasklet implements Tasklet {
    private final ExceptionGenerationPort exceptionGenerationPort;
    @Override
    public @Nullable RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {


        return null;
    }
}