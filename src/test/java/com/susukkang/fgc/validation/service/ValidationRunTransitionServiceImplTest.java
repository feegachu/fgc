package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ValidationRunTransitionServiceImpl 단위테스트.
 * ValidationRunMapper를 mock으로 대체해 "찾기 → 전이 판단 → 조건부 UPDATE 호출" 흐름만 검증한다.
 * CapCheckServiceImplTest를 참고 패턴으로 삼을 것.
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunTransitionServiceImplTest {

    @Mock
    private ValidationRunMapper validationRunMapper;

    private ValidationRunTransitionServiceImpl service;

    // TODO(FUN-041): @BeforeEach 에서 service = new ValidationRunTransitionServiceImpl(validationRunMapper);

    @Test
    @Disabled("TODO(FUN-041): 실행이 없으면(findById null) COMMON_004 FgcBusinessException")
    void throwsNotFoundWhenRunDoesNotExist() {
    }

    @Test
    @Disabled("TODO(FUN-041): 허용되지 않은 전이 요청이면 VRUN_004, updateStatusIfCurrent는 호출되지 않아야 함")
    void throwsInvalidTransitionForDisallowedTransition() {
    }

    @Test
    @Disabled("TODO(FUN-041): 허용된 전이인데 updateStatusIfCurrent가 0을 반환하면 VRUN_005(경합)")
    void throwsStateConflictWhenConditionalUpdateAffectsZeroRows() {
    }

    @Test
    @Disabled("TODO(FUN-041): 정상 케이스 — updateStatusIfCurrent가 1을 반환하면 최신 행을 반환")
    void returnsUpdatedRowOnSuccessfulTransition() {
    }

    @Test
    @Disabled("TODO(FUN-041): FINALIZED 상태의 실행에 어떤 target을 줘도 VRUN_004로 막히는지")
    void blocksAnyTransitionFromFinalized() {
    }
}
