package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.common.exception.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.susukkang.fgc.assistant.dto.PicoAskResponse.*;

class PicoAskServiceTest {
    LlmGateway gateway; PicoAskService service; PicoGuideCatalog catalog; PicoPaymentContextService contexts;
    @BeforeEach void setup() throws Exception {
        gateway=mock(LlmGateway.class); var mapper=new ObjectMapper(); catalog=new PicoGuideCatalog(mapper); contexts=mock(PicoPaymentContextService.class); service=new PicoAskService(gateway,catalog,mapper,contexts); role("SETTLEMENT");
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void role(String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)))); }
    PicoAskRequest request(String question) { return new PicoAskRequest(question,"TRAN-W02",List.of(),"r1","1.0",List.of()); }
    PicoAskService.ModelAnswer answer(String message,String source) { return new PicoAskService.ModelAnswer(Decision.ANSWER_ONLY,message,List.of(source)); }
    @Test void answersFromRegisteredGuideAndNeverSendsRawQuestionOrPrivateData() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("초안 저장은 작성 내용을 보관하는 절차이며 지급 확정과 구분됩니다.","payment.draft:overview"));
        var response=service.ask(request("초안 저장하면 지급 끝? 고객 김철수 123456789 private@example.com"));
        assertThat(response.source()).isEqualTo(Source.AI); assertThat(response.validatedGuide()).isNull(); assertThat(response.contextRevision()).isEqualTo("r1");
        assertThat(response.evidenceRefs()).extracting(Evidence::workflowId).containsExactly("payment.draft");
        var captured=ArgumentCaptor.forClass(LlmGateway.LlmRequest.class); verify(gateway).call(captured.capture(),eq(PicoAskService.ModelAnswer.class));
        assertThat(captured.getValue().data()).contains("intentTerms","초안").doesNotContain("김철수","123456789","private@example.com","초안 저장하면 지급 끝?");
        assertThat(captured.getValue().instructions()).contains("목록/순서 번호 없이", "evidenceChunkIds 배열에만");
        assertThat(captured.getValue().relatedEntityId()).isNull(); verifyNoMoreInteractions(gateway);
    }
    @Test void readonlyRoleCannotObtainProcessGuide() {
        role("COMPLIANCE"); var response=service.ask(request("초안 저장 방법")); assertThat(response.fallbackReason()).isEqualTo("PERMISSION_DENIED"); verifyNoInteractions(gateway);
    }
    @Test void asksClarificationForTiesAndPaymentCorrection() {
        assertThat(service.ask(request("초안 확정")).decision()).isEqualTo(Decision.ASK_CLARIFICATION);
        assertThat(service.ask(request("지급액을 잘못 입력했어요")).fallbackReason()).isEqualTo("PAYMENT_STATE_REQUIRED"); verifyNoInteractions(gateway);
    }
    @Test void noTopicVersionMismatchEntitiesAndHistoryNeverCallModel() {
        assertThat(service.ask(request("오늘 날씨")).decision()).isEqualTo(Decision.UNSUPPORTED);
        var req=request("초안 저장");
        assertThatThrownBy(() -> service.ask(new PicoAskRequest(req.question(),req.screenId(),List.of(),"r1","2.0",List.of()))).isInstanceOfSatisfying(FgcBusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_GUIDE_VERSION_MISMATCH));
        assertThat(service.ask(new PicoAskRequest(req.question(),req.screenId(),List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.CONTRACT,77L)),"r1","1.0",List.of())).fallbackReason()).isEqualTo("ENTITY_STATE_NOT_SUPPORTED");
        assertThat(service.ask(new PicoAskRequest(req.question(),req.screenId(),List.of(),"r1","1.0",List.of(new PicoAskRequest.Turn(PicoAskRequest.Role.USER,"private data")))).fallbackReason()).isEqualTo("HISTORY_NOT_SUPPORTED"); verifyNoInteractions(gateway);
    }
    @Test void financialQuestionUsesProgramCalculationInsteadOfModelArithmetic() {
        assertThat(service.ask(request("이번 달 수수료 얼마야")).fallbackReason()).isEqualTo("CALCULATION_REQUIRED"); verifyNoInteractions(gateway);
    }
    @Test void rejectsForgedSourcesNumbersCompletionClaimsAndExecutableText() {
        for(var bad:List.of(answer("외부로 이동하세요","evil:source"),answer("오만원 50000원입니다","payment.draft:overview"),answer("오만원 지급을 추천합니다","payment.draft:overview"),answer("모집수수료의 천이백 퍼센트 한도입니다","payment.draft:overview"),answer("십이 배까지 지급됩니다","payment.draft:overview"),answer("오 할입니다","payment.draft:overview"),answer("두 배로 지급됩니다","payment.draft:overview"),answer("５만원입니다","payment.draft:overview"),answer("1. 대상을 확인하세요","payment.draft:overview"),answer("javaScript:alert 실행","payment.draft:overview"),answer(java.text.Normalizer.normalize("지급했습니다",java.text.Normalizer.Form.NFD),"payment.draft:overview"),answer("지급했습니다","payment.draft:overview"),answer("https://evil.example 실행","payment.draft:overview"))) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(bad);
            var response=service.ask(request("초안 저장")); assertThat(response.source()).isEqualTo(Source.FIXED); assertThat(response.fallbackReason()).isEqualTo("INVALID_AI_OUTPUT"); assertThat(response.message()).doesNotContain("evil","50000","지급했습니다");
        }
    }
    @Test void gatewayFailureKeepsFixedGuideAndCannotStartWorkflow() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenThrow(new FgcBusinessException(FgcErrorCode.AI_001));
        var response=service.ask(request("초안 저장")); assertThat(response.source()).isEqualTo(Source.FIXED); assertThat(response.fallbackReason()).isEqualTo("AI_UNAVAILABLE"); assertThat(response.evidenceRefs()).isNotEmpty(); assertThat(response.validatedGuide()).isNull();
    }
    @Test void sharedCatalogOnlyContainsKnownTargetsAndBoundedEvidence() {
        assertThat(catalog.definitions()).hasSize(3);
        for(var guide:catalog.definitions()) { assertThat(guide.guideVersion()).isEqualTo("1.0"); assertThat(catalog.chunks(guide)).hasSizeLessThanOrEqualTo(8); assertThat(catalog.chunks(guide)).allSatisfy(c->assertThat(c.workflowId()).isEqualTo(guide.workflowId())); }
    }
    @Test void unapprovedScreenNeverCallsModel() {
        assertThatThrownBy(() -> service.ask(new PicoAskRequest("초안 저장", "XXXX-W99", List.of(), "r1", "1.0", List.of())))
                .isInstanceOfSatisfying(FgcBusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
        verifyNoInteractions(gateway);
    }
    @Test void normalProcessAndSupportWordsAreNotFinancialQuantities() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("초안 저장 프로세스는 지급 확정과 구분됩니다. 지원 업무를 확인하세요.","payment.draft:overview"));
        assertThat(service.ask(request("초안 저장 방법")).source()).isEqualTo(Source.AI);
    }
    @Test void acceptsCalculationLookupWordingWithoutMistakingItForQuantity() {
        for (var message : List.of("실제 금액은 계산 조회로 확인하세요.", "계산 조회를 확인하세요.", "계산 조회는 별도로 진행하세요.", "계산 조회가 필요합니다.", "계산 조회만 진행하세요.", "계산 조회까지 진행하세요.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("초안 저장")).source()).as(message).isEqualTo(Source.AI);
        }
    }
    @Test void sharedCatalogRegistersBothApprovedSavedDraftBranches() {
        var guide = catalog.definitions().stream().filter(g -> g.workflowId().equals("payment.confirm")).findFirst().orElseThrow();
        var scope = guide.steps().stream().filter(step -> step.stepId().equals("payment.scope")).findFirst().orElseThrow();
        assertThat(scope.next()).containsExactly(
                new PicoGuideCatalog.Next("CURRENT_DRAFT_ALREADY_SAVED", "payment.precheck"),
                new PicoGuideCatalog.Next("DRAFT_SAVE_REQUIRED", "payment.save"));
    }
    @Test void rejectsGeneratedInstallmentCountsWithoutDisablingLookupWording() {
        for (var message : List.of("수수료는 스물네 회로 나누어 지급됩니다.", "칠 회까지 분할 지급됩니다.", "스물네회로 분할 지급됩니다.", "일조회로 지급됩니다.", "일조 회로 지급됩니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            var response = service.ask(request("초안 저장"));
            assertThat(response.source()).as(message).isEqualTo(Source.FIXED);
            assertThat(response.fallbackReason()).isEqualTo("INVALID_AI_OUTPUT");
        }
    }
    @Test void rejectsFinancialQuantitiesWithKoreanParticlesAndKeepsOrdinaryGuideWords() {
        for (var message : List.of("천만 원을 초년도에 지급합니다.", "백만 원씩 나누어 지급됩니다.", "스물네 회에 걸쳐 분할 지급됩니다.", "칠 년에 걸쳐 분급됩니다.", "오 할이 환수됩니다.", "삼 회차로 지급됩니다.", "팔만 원짜리 지급입니다.", "사 년간 분할됩니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("초안 저장")).source()).as(message).isEqualTo(Source.FIXED);
        }
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("입력한 원본을 원칙에 따라 확인하고 배정 근거를 조회하세요.","payment.draft:overview"));
        assertThat(service.ask(request("초안 저장")).source()).isEqualTo(Source.AI);
    }
    PicoAskRequest paymentRequest(String question, long id) {
        return new PicoAskRequest(question, "TRAN-W02", List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,id)), "r1", "1.0", List.of());
    }
    @Test void authorizesStoredDraftBeforeModelAndNeverSendsItsIdentifiersOrState() {
        long id = 9876543210123L;
        when(contexts.read(id)).thenReturn(new PicoPaymentContextService.Snapshot(com.susukkang.fgc.common.code.CommissionPaymentStatus.DRAFT));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("초안 저장은 지급 확정과 구분됩니다.","payment.draft:overview"));
        var response = service.ask(paymentRequest("초안 저장 방법 고객 김철수 1234567.89원", id));
        assertThat(response.source()).isEqualTo(Source.AI); assertThat(response.validatedGuide()).isNull();
        var order = inOrder(contexts, gateway); order.verify(contexts).read(id); order.verify(gateway).call(any(),eq(PicoAskService.ModelAnswer.class));
        var captured = ArgumentCaptor.forClass(LlmGateway.LlmRequest.class); verify(gateway).call(captured.capture(),eq(PicoAskService.ModelAnswer.class));
        assertThat(captured.getValue().data()).doesNotContain("9876543210123", "1234567.89", "김철수", "\"paymentStatus\"", "\"entityRefs\"");
        assertThat(captured.getValue().relatedEntityId()).isNull();
    }
    @Test void confirmedAndCancelledPaymentsNeverRequestAProcessingExplanation() {
        for (var status : List.of(com.susukkang.fgc.common.code.CommissionPaymentStatus.CONFIRMED, com.susukkang.fgc.common.code.CommissionPaymentStatus.CANCELLED)) {
            when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(status));
            var response = service.ask(paymentRequest("지급 확정 방법",77L));
            assertThat(response.source()).isEqualTo(Source.FIXED); assertThat(response.validatedGuide()).isNull();
            assertThat(response.fallbackReason()).isIn("PAYMENT_ALREADY_CONFIRMED","PAYMENT_CANCELLED");
        }
        verifyNoInteractions(gateway);
    }
    @Test void readOnlyRoleCanReadPaymentStateButCannotObtainProcessingInstructions() {
        role("COMPLIANCE");
        when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(com.susukkang.fgc.common.code.CommissionPaymentStatus.DRAFT));
        assertThat(service.ask(paymentRequest("현재 지급 상태가 확정인가요?",77L)).fallbackReason()).isEqualTo("PAYMENT_DRAFT_STATE");
        assertThat(service.ask(paymentRequest("초안 저장 방법",77L)).fallbackReason()).isEqualTo("PERMISSION_DENIED");
        verify(contexts,times(1)).read(77L); verifyNoInteractions(gateway);
    }
    @Test void multipleEntitiesAndUnauthorizedTargetsNeverReachTheModel() {
        var request = new PicoAskRequest("초안 저장", "TRAN-W02", List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L),new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,88L)), "r1", "1.0", List.of());
        assertThat(service.ask(request).fallbackReason()).isEqualTo("SINGLE_PAYMENT_REQUIRED"); verifyNoInteractions(contexts);
        when(contexts.read(77L)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.ask(paymentRequest("초안 저장",77L))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(gateway);
    }
    @Test void correctionUsesActualDraftStateWithoutCallingTheModel() {
        when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(com.susukkang.fgc.common.code.CommissionPaymentStatus.DRAFT));
        assertThat(service.ask(paymentRequest("지급액을 잘못 입력했어요",77L)).fallbackReason()).isEqualTo("DRAFT_CORRECTION_REVIEW");
        verifyNoInteractions(gateway);
    }
}
