package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
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
    static com.susukkang.fgc.ai.config.LlmProperties limits(int maximum) {
        return new com.susukkang.fgc.ai.config.LlmProperties("", "https://api.openai.com/v1", "gpt-4o-mini", java.time.Duration.ofSeconds(5),maximum,1024,java.time.Duration.ofMinutes(10));
    }
    @BeforeEach void setup() throws Exception {
        gateway=mock(LlmGateway.class); var mapper=new ObjectMapper(); catalog=new PicoGuideCatalog(mapper); contexts=mock(PicoPaymentContextService.class); service=new PicoAskService(gateway,catalog,mapper,contexts,limits(20000)); role("SETTLEMENT");
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of("payment.draft")));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void role(String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)))); }
    PicoAskRequest request(String question) { return new PicoAskRequest(question,"TRAN-W02",List.of(),"r1","1.0",List.of()); }
    PicoAskService.ModelAnswer answer(String message,String source) { return new PicoAskService.ModelAnswer(Decision.ANSWER_ONLY,message,List.of(source)); }
    @Test void preservesMeaningAndRedactsUserAuthoredPrivatePatternsInBothStages() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("초안 저장은 지급 확정과 구분됩니다.","payment.draft:overview"));
        var response=service.ask(request("초안 저장하면 지급 끝? 고객 김철수 123456789 private@example.com sk-testabc123456789"));
        assertThat(response.source()).isEqualTo(Source.AI);
        assertThat(response.evidenceRefs()).extracting(Evidence::workflowId).containsExactly("payment.draft");
        var prompts=ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway).call(prompts.capture(),eq(PicoAskService.GuideSelection.class));
        verify(gateway).call(prompts.capture(),eq(PicoAskService.ModelAnswer.class));
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> {
            assertThat(prompt.data()).contains("초안 저장하면 지급 끝?").doesNotContain("김철수","123456789","private@example.com","sk-testabc123456789");
            assertThat(prompt.relatedEntityId()).isNull();
        });
        assertThat(prompts.getAllValues().getLast().data()).contains("payment.draft:overview").doesNotContain("payment.confirm:overview");
    }
    @Test void followupWithoutAnyRegisteredKeywordUsesConversationInsteadOfFixedReply() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("지급 대상 영역에서 검색한 뒤 원하는 수취인 항목을 선택하세요.","payment.draft:payment.scope"));
        var request = new PicoAskRequest("선택 어디서 해?", "TRAN-W02", List.of(), "r2", "1.0", List.of(
            new PicoAskRequest.Turn(PicoAskRequest.Role.USER,"지급 초안을 작성하고 싶어"),
            new PicoAskRequest.Turn(PicoAskRequest.Role.ASSISTANT,"지급 대상을 먼저 선택하세요.")));
        assertThat(service.ask(request).source()).isEqualTo(Source.AI);
        var prompt=ArgumentCaptor.forClass(LlmGateway.LlmRequest.class); verify(gateway).call(prompt.capture(),eq(PicoAskService.GuideSelection.class));
        assertThat(prompt.getValue().data()).contains("선택 어디서 해?","지급 초안을 작성하고 싶어","지급 대상을 먼저 선택하세요.");
    }
    @Test void readonlyRetrievalCannotSelectProcessingOrUnpermittedAuditGuides() {
        role("GA_ADMIN");
        assertThat(service.ask(request("초안 저장 방법")).fallbackReason()).isEqualTo("INVALID_AI_SELECTION");
        var prompt=ArgumentCaptor.forClass(LlmGateway.LlmRequest.class); verify(gateway).call(prompt.capture(),eq(PicoAskService.GuideSelection.class));
        assertThat(prompt.getValue().data()).doesNotContain("payment.draft", "payment.confirm", "audit.review", "agent.portfolio");
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void semanticRetrievalRejectsUnknownAndDuplicateWorkflowIds() {
        for (var ids : List.of(List.of("invented.workflow"), List.of("payment.draft","payment.draft"))) {
            when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(ids));
            assertThat(service.ask(request("어떻게 시작해?")).fallbackReason()).isEqualTo("INVALID_AI_SELECTION");
        }
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void financialQuestionUsesModelForGuidanceButNeverAllowsGeneratedAmounts() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("실제 금액은 프로그램의 계산 조회에서 확인하세요.","payment.draft:overview"));
        assertThat(service.ask(request("이번 달 수수료 얼마야")).source()).isEqualTo(Source.AI);
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("오만원을 지급하세요.","payment.draft:overview"));
        assertThat(service.ask(request("얼마야")).fallbackReason()).isEqualTo("INVALID_AI_OUTPUT");
    }
    @Test void storedClosedStateIsAuthorizedAndDeterministicallyBlocksPaymentGuidanceWithoutExposingState() {
        for (var state : List.of(CommissionPaymentStatus.CONFIRMED, CommissionPaymentStatus.CANCELLED)) {
            when(contexts.read(9876543210123L)).thenReturn(new PicoPaymentContextService.Snapshot(state));
            var req = new PicoAskRequest("고객 김철수 1234567.89원 선택 어디서?","TRAN-W02",List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,9876543210123L)),"r1","1.0",List.of());
            var reply = service.ask(req);
            assertThat(reply.source()).isEqualTo(Source.FIXED);
            assertThat(reply.fallbackReason()).isIn("PAYMENT_ALREADY_CONFIRMED","PAYMENT_CANCELLED");
            assertThat(reply.evidenceRefs()).isEmpty();
        }
        var prompts=ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway,times(2)).call(prompts.capture(),eq(PicoAskService.GuideSelection.class));
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> assertThat(prompt.data()).doesNotContain("storedPaymentState","CONFIRMED","CANCELLED","9876543210123","1234567.89","김철수"));
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void aClosedPaymentDoesNotPreventQuestionsAboutAnotherBusinessWorkflow() {
        when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.CONFIRMED));
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of("contract.find")));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("보험계약 목록의 검색 조건에서 조회하세요.","contract.find:overview"));
        var request = new PicoAskRequest("다른 보험계약을 찾고 싶어","TRAN-W02",List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)),"r1","1.0",List.of());
        assertThat(service.ask(request).source()).isEqualTo(Source.AI);
    }
    @Test void unsupportedSingleEntityAndMultipleReferencesAreDifferentContracts() {
        var contract = new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.CONTRACT,1L);
        var unsupported = service.ask(new PicoAskRequest("이 계약 상태","CONT-W01",List.of(contract),"r1","1.0",List.of()));
        assertThat(unsupported.decision()).isEqualTo(Decision.UNSUPPORTED);
        assertThat(unsupported.fallbackReason()).isEqualTo("ENTITY_STATE_NOT_SUPPORTED");
        var multiple = service.ask(new PicoAskRequest("상태","CONT-W01",List.of(contract,contract),"r1","1.0",List.of()));
        assertThat(multiple.decision()).isEqualTo(Decision.ASK_CLARIFICATION);
        assertThat(multiple.fallbackReason()).isEqualTo("SINGLE_PAYMENT_REQUIRED");
        verifyNoInteractions(contexts,gateway);
    }
    @Test void unauthorizedEntityNeverReachesExternalProvider() {
        when(contexts.read(77L)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.ask(new PicoAskRequest("어디서?","TRAN-W02",List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)),"r1","1.0",List.of())))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(gateway);
    }
    @Test void unsupportedQuestionMayBeAnsweredAsUnsupportedWithoutFabricatedEvidence() {
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of()));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(new PicoAskService.ModelAnswer(Decision.UNSUPPORTED,"업무 가이드에 없는 내용입니다. 정산 업무에 대해 질문해주세요.",List.of()));
        assertThat(service.ask(request("오늘 날씨")).decision()).isEqualTo(Decision.UNSUPPORTED);
    }
    @Test void providerFailureIsExplicitAndDoesNotMasqueradeAsAWorkflowAnswer() {
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenThrow(new FgcBusinessException(FgcErrorCode.AI_001));
        var response=service.ask(request("어디서 선택해?"));
        assertThat(response.source()).isEqualTo(Source.FIXED);
        assertThat(response.decision()).isEqualTo(Decision.ASK_CLARIFICATION);
        assertThat(response.evidenceRefs()).isEmpty(); assertThat(response.validatedGuide()).isNull();
    }
    @Test void invalidScreenAndVersionNeverReachModel() {
        assertThatThrownBy(() -> service.ask(new PicoAskRequest("어디서?","XXXX-W99",List.of(),"r1","1.0",List.of()))).isInstanceOf(FgcBusinessException.class);
        assertThatThrownBy(() -> service.ask(new PicoAskRequest("어디서?","CONT-W01",List.of(),"r1","2.0",List.of()))).isInstanceOf(FgcBusinessException.class);
        verifyNoInteractions(gateway);
    }
    @Test void sourceLocationRetrievalUsesLabelsAndReturnsOnlyRegisteredHelp() {
        var guide = catalog.definitions().stream().filter(item -> item.workflowId().equals("contract.register")).findFirst().orElseThrow();
        assertThat(catalog.chunks(guide,"계약번호 입력은 어디서 하나요").stream().filter(chunk -> chunk.stepId() != null && chunk.stepId().startsWith("help.")))
            .isNotEmpty().allSatisfy(chunk -> assertThat(chunk.chunkId()).startsWith("contract.register:help."));
        assertThat(catalog.chunks(guide,"zzzzzz").stream().filter(chunk -> chunk.stepId() != null && chunk.stepId().startsWith("help."))).isEmpty();
    }
    @Test void sharedCatalogIncludesAllRegisteredBusinessScreens() {
        assertThat(catalog.definitions()).hasSizeGreaterThan(3);
        assertThat(catalog.definitions().stream().flatMap(guide -> guide.steps().stream()).map(PicoGuideCatalog.Step::screenId).distinct())
            .contains("CONT-W01","CONT-W02","CONT-W03","SCHE-W01","SCHE-W02","TRAN-W01","TRAN-W02","BASE-W01","POL-W01","CAP-W01","ARB-W01","LEDG-W01","RECO-W01","EXCP-W01","VRUN-W01","VRUN-W02","AUDT-W01","DASH-W01","AGNT-W01");
    }
    @Test void rejectsForgedSourcesNumbersCompletionClaimsAndExecutableText() {
        for(var bad:List.of(answer("외부로 이동하세요","evil:source"),answer("오만원 50000원입니다","payment.draft:overview"),answer("오만원 지급을 추천합니다","payment.draft:overview"),answer("모집수수료의 천이백 퍼센트 한도입니다","payment.draft:overview"),answer("십이 배까지 지급됩니다","payment.draft:overview"),answer("오 할입니다","payment.draft:overview"),answer("두 배로 지급됩니다","payment.draft:overview"),answer("５만원입니다","payment.draft:overview"),answer("1. 대상을 확인하세요","payment.draft:overview"),answer("javaScript:alert 실행","payment.draft:overview"),answer(java.text.Normalizer.normalize("지급했습니다",java.text.Normalizer.Form.NFD),"payment.draft:overview"),answer("지급했습니다","payment.draft:overview"),answer("https://evil.example 실행","payment.draft:overview"))) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(bad);
            var response=service.ask(request("초안 저장")); assertThat(response.source()).isEqualTo(Source.FIXED); assertThat(response.fallbackReason()).isEqualTo("INVALID_AI_OUTPUT"); assertThat(response.message()).doesNotContain("evil","50000","지급했습니다");
        }
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
    @Test void largeEvidenceAndHistoryAreBoundedBeforeBothProviderCalls() throws Exception {
        var mapper = new ObjectMapper();
        service = new PicoAskService(gateway,catalog,mapper,contexts,limits(12000));
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
            .thenReturn(new PicoAskService.GuideSelection(List.of("contract.detail","settlement.journey")));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
            .thenReturn(answer("계약 상세의 실제 근거를 확인하세요.","contract.detail:overview"));
        var turns = java.util.stream.IntStream.range(0,6).mapToObj(index -> new PicoAskRequest.Turn(
            index % 2 == 0 ? PicoAskRequest.Role.USER : PicoAskRequest.Role.ASSISTANT,
            ("보험계약 스케줄 지급 대사 업무 안내 ".repeat(150)).substring(0,2000))).toList();
        var req = new PicoAskRequest(("계약 1a ".repeat(400)).substring(0,2000),"CONT-W03",List.of(),"r1","1.0",turns);
        assertThat(service.ask(req).source()).isEqualTo(Source.AI);
        var prompts = ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway,times(2)).call(prompts.capture(),any());
        for (var prompt : prompts.getAllValues()) {
            assertThat(prompt.data().length()).isLessThanOrEqualTo(12000);
            assertThat(mapper.readTree(prompt.data()).path("question").asText().length()).isLessThanOrEqualTo(2000);
        }
        assertThat(mapper.readTree(prompts.getAllValues().getLast().data()).path("evidence").size()).isPositive();
    }
    @Test void impossibleInputLimitIsClarificationNotProviderFailure() {
        service = new PicoAskService(gateway,catalog,new ObjectMapper(),contexts,limits(64));
        var response = service.ask(request("계약 안내"));
        assertThat(response.fallbackReason()).isEqualTo("INPUT_TOO_LARGE");
        assertThat(response.message()).contains("짧게 질문");
        verifyNoInteractions(gateway);
    }
}

