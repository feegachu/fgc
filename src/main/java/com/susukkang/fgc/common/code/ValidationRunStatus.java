package com.susukkang.fgc.common.code;


public enum ValidationRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    FINALIZED;

    public boolean canTransitionTo(ValidationRunStatus target) {
        return switch (this){
            case CREATED -> target == RUNNING;
            case RUNNING -> target == COMPLETED || target == FAILED;
            case COMPLETED -> target == FINALIZED;
            case FAILED -> target == RUNNING;
            case FINALIZED -> false;
        };
    }
}
