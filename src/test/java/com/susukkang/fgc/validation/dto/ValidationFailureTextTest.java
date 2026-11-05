package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.dashboard.dto.RecentValidationRunResponse;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 옛 실행의 예외 원문이 저장돼 있어도 실패 사유 응답 세 곳에 나가지 않는다 (IF-API-03, 47, 49). */
class ValidationFailureTextTest {
    private static final String OLD_RAW_MESSAGE = "JDBC exception executing SQL [WITH eligible_contract AS (SELECT 1)]";
    private static final String USER_SENTENCE = "2단계 처리 중 오류가 발생했습니다. 요청번호 abc.def_12-3를 담당자에게 알려주세요.";

    @Test
    void keepsAUserSentenceWithItsRequestNumber() {
        assertThat(ValidationFailureText.forResponse(USER_SENTENCE)).isEqualTo(USER_SENTENCE);
        assertThat(ValidationFailureText.forResponse("GA→설계사 1,200% 한도 초과")).isEqualTo("GA→설계사 1,200% 한도 초과");
    }

    @Test
    void contractNumberIsNotDeveloperTextLikeTheScreenRule() {
        String sentence = "계약 FGC-FGL01-202607-0001 자료를 확인하세요.";
        assertThat(ValidationFailureText.forResponse(sentence)).isEqualTo(sentence);
        assertThat(ValidationFailureText.forResponse("FGC-VRUN-003 계약 FGC-FGL01-202607-0001")).isNull();
    }

    @Test
    void returnsNullWhenNoUserSentenceRemains() {
        assertThat(ValidationFailureText.forResponse(OLD_RAW_MESSAGE)).isNull();
        assertThat(ValidationFailureText.forResponse(null)).isNull();
    }

    @Test
    void keepsTheUserSentencesOfAMixedMessage() {
        assertThat(ValidationFailureText.forResponse(USER_SENTENCE + " | " + OLD_RAW_MESSAGE)).isEqualTo(USER_SENTENCE);
    }

    @Test
    void everyResponseThatCarriesTheMessageDropsTheRawText() {
        ValidationRunRow progress = new ValidationRunRow();
        progress.setStatus("FAILED");
        progress.setCurrentStep(2);
        progress.setFailureMessage(OLD_RAW_MESSAGE);
        assertThat(ValidationRunProgressResponse.from(progress).failureMessage()).isNull();

        ValidationRunListRow item = new ValidationRunListRow();
        item.setRunType("MONTHLY");
        item.setStatus("FAILED");
        item.setFailureMessage(OLD_RAW_MESSAGE);
        assertThat(ValidationRunItemResponse.from(item).failureMessage()).isNull();

        assertThat(RecentValidationRunResponse.from(new RecentValidationRunRow(1L, LocalDate.of(2026, 7, 1), 1, "MONTHLY",
                "FAILED", 2, null, null, null, null, null, OLD_RAW_MESSAGE)).failureMessage()).isNull();
    }
}
