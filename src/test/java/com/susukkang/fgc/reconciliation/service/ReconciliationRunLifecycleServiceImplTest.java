package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 대사 실행 상태 전이 서비스 단위 테스트
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationRunLifecycleServiceImplTest {

    @Mock
    private ReconciliationRunMapper reconciliationRunMapper;

    private ReconciliationRunLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationRunLifecycleServiceImpl(reconciliationRunMapper);
    }

    @Test
    void 기존_DB_전이로_시작_완료_실패를_처리한다() {
        given(reconciliationRunMapper.transitionToRunning(11L)).willReturn(1);
        given(reconciliationRunMapper.transitionToCompleted(12L)).willReturn(1);
        given(reconciliationRunMapper.transitionToFailed(13L)).willReturn(1);

        service.start(11L);
        service.complete(12L);
        service.fail(13L);

        verify(reconciliationRunMapper).transitionToRunning(11L);
        verify(reconciliationRunMapper).transitionToCompleted(12L);
        verify(reconciliationRunMapper).transitionToFailed(13L);
    }

    @Test
    void 허용되지_않은_상태전이는_공통_서버오류로_중단한다() {
        given(reconciliationRunMapper.transitionToCompleted(11L)).willReturn(0);

        assertThatThrownBy(() -> service.complete(11L))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_500));
    }
}
