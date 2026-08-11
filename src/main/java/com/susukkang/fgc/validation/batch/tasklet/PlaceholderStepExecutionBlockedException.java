package com.susukkang.fgc.validation.batch.tasklet;

public class PlaceholderStepExecutionBlockedException extends RuntimeException {
    public PlaceholderStepExecutionBlockedException(String stepDescription) {
        super(stepDescription + " is not implemented; validation run cannot be completed.");
    }
}
