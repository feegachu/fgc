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
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of("payment.draft"), false));
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
    @Test void complianceEvidenceOmitsProcessingStepsAndRejectsTheirCitation() throws Exception {
        role("COMPLIANCE");
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
                .thenReturn(new PicoAskService.GuideSelection(List.of("ledger.review"), false));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
                .thenReturn(answer("원장 목록에서 상태를 조회하세요.","ledger.review:overview"));
        assertThat(service.ask(request("역분개 사유 작성과 확인 버튼 위치" )).source()).isEqualTo(Source.AI);
        var prompts = ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway,times(2)).call(prompts.capture(),any());
        var mapper = new ObjectMapper();
        var selection = mapper.readTree(prompts.getAllValues().getFirst().data()).path("availableGuides");
        var selected = java.util.stream.StreamSupport.stream(selection.spliterator(),false)
                .filter(guide -> guide.path("workflowId").asText().equals("ledger.review")).findFirst().orElseThrow();
        assertThat(selected.path("description").asText()).contains("원장 검색").doesNotContain("역분개 사유 작성", "실제 반영 확인");
        var evidence = mapper.readTree(prompts.getAllValues().getLast().data()).path("evidence");
        assertThat(evidence.toString()).contains("ledger.review.step.1", "ledger.review.step.2")
                .doesNotContain("ledger.review.step.3", "ledger.review.step.4", "역분개 사유 작성");
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
                .thenReturn(answer("근거를 확인하세요.","ledger.review:ledger.review.step.3"));
        assertThat(service.ask(request("역분개 방법")).fallbackReason()).isEqualTo("INVALID_AI_OUTPUT");
    }
    @Test void selectionKnowsEachGuideScreenSoCurrentScreenFollowupsStayOnThatScreen() throws Exception {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("수령 설계사 칸에서 고르세요.","payment.draft:overview"));
        assertThat(service.ask(request("그 화면에서 수령인은 어디에서 선택해?")).source()).isEqualTo(Source.AI);
        var prompts = ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway,times(2)).call(prompts.capture(),any());
        var selection = prompts.getAllValues().getFirst();
        assertThat(selection.instructions()).contains("screens에 screenId가 있는 안내");
        var screens = new java.util.HashMap<String, String>();
        new ObjectMapper().readTree(selection.data()).path("availableGuides")
                .forEach(guide -> screens.put(guide.path("workflowId").asText(), guide.path("screens").toString()));
        assertThat(screens.get("payment.draft")).contains("TRAN-W02");
        assertThat(screens.get("contract.register")).contains("CONT-W03").doesNotContain("TRAN-W02");
    }
    @Test void validationFinalizationUsesItsOwnRoleInsteadOfPaymentProcessingPermission() {
        var guide = catalog.definitions().stream().filter(item -> item.workflowId().equals("validation.detail")).findFirst().orElseThrow();
        assertThat(catalog.chunks(guide,"확정",List.of("GA_ADMIN"))).extracting(PicoGuideCatalog.Chunk::stepId)
                .contains("validation.detail.step.3");
        assertThat(catalog.chunks(guide,"확정",List.of("SETTLEMENT"))).extracting(PicoGuideCatalog.Chunk::stepId)
                .doesNotContain("validation.detail.step.3");
        role("GA_ADMIN");
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
                .thenReturn(new PicoAskService.GuideSelection(List.of("validation.detail"), false));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
                .thenReturn(answer("확정 조건을 확인하세요.","validation.detail:validation.detail.step.3"));
        assertThat(service.ask(request("검증 확정 조건")).source()).isEqualTo(Source.AI);
    }
    @Test void semanticRetrievalRejectsUnknownAndDuplicateWorkflowIds() {
        for (var ids : List.of(List.of("invented.workflow"), List.of("payment.draft","payment.draft"))) {
            when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(ids, false));
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
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of("contract.find"), false));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("보험계약 목록의 검색 조건에서 조회하세요.","contract.find:overview"));
        var request = new PicoAskRequest("다른 보험계약을 찾고 싶어","TRAN-W02",List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)),"r1","1.0",List.of());
        assertThat(service.ask(request).source()).isEqualTo(Source.AI);
    }
    @Test void changedPaymentStateOrContentDuringModelAnswerInvalidatesTheReply() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
                .thenReturn(answer("초안 저장은 지급 확정과 구분됩니다.","payment.draft:overview"));
        for (var current : List.of(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.CONFIRMED,"new"),
                new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.CANCELLED,"new"),
                new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.DRAFT,"edited"))) {
            when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.DRAFT,"original"),current);
            var reply = service.ask(new PicoAskRequest("저장 방법", "TRAN-W02", List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)), "r1", "1.0", List.of()));
            assertThat(reply.source()).isEqualTo(Source.FIXED);
            assertThat(reply.fallbackReason()).isEqualTo("PAYMENT_CONTEXT_CHANGED");
            assertThat(reply.evidenceRefs()).isEmpty();
        }
    }
    @Test void changedClosedPaymentWhileSelectingWorkflowDoesNotReportStaleStatus() {
        when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.CONFIRMED,"confirmed"),
                new PicoPaymentContextService.Snapshot(CommissionPaymentStatus.CANCELLED,"cancelled"));
        var reply = service.ask(new PicoAskRequest("현재 상태", "TRAN-W02", List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)), "r1", "1.0", List.of()));
        assertThat(reply.fallbackReason()).isEqualTo("PAYMENT_CONTEXT_CHANGED");
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void disabledWorkflowAndItsGeneratedHelpAreExcludedWithoutPreventingStartup() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper());
        doAnswer(invocation -> {
            Object value = invocation.callRealMethod();
            if (value instanceof List<?> values && !values.isEmpty() && values.getFirst() instanceof PicoGuideCatalog.Definition) {
                return values.stream().map(item -> {
                    var guide = (PicoGuideCatalog.Definition) item;
                    return new PicoGuideCatalog.Definition(guide.workflowId(),guide.guideVersion(),guide.title(),guide.description(),
                            guide.capability(),guide.keywords(),guide.steps(),!guide.workflowId().equals("settlement.journey"),guide.roles());
                }).toList();
            }
            return value;
        }).when(mapper).readValue(any(java.io.InputStream.class),any(com.fasterxml.jackson.core.type.TypeReference.class));
        assertThat(new PicoGuideCatalog(mapper).definitions()).extracting(PicoGuideCatalog.Definition::workflowId)
                .contains("payment.draft").doesNotContain("settlement.journey");
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
    @Test void emptyGuideSelectionGetsFixedOutOfScopeReplyWithoutSecondModelCall() {
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of(), true));
        var reply = service.ask(request("오늘 날씨"));
        assertThat(reply.decision()).isEqualTo(Decision.UNSUPPORTED);
        assertThat(reply.source()).isEqualTo(Source.FIXED);
        assertThat(reply.evidenceRefs()).isEmpty();
        assertThat(reply.fallbackReason()).isEqualTo("OUT_OF_SCOPE");
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void ambiguousInScopeQuestionIsAskedBackWithoutSecondModelCall() {
        // FGC-FUN-073: 범위 안이지만 대상이 모호하면 '범위 밖'이 아니라 다시 묻는다.
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of(), false));
        var reply = service.ask(request("이거 어떻게 해요?"));
        assertThat(reply.decision()).isEqualTo(Decision.ASK_CLARIFICATION);
        assertThat(reply.source()).isEqualTo(Source.FIXED);
        assertThat(reply.fallbackReason()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(reply.evidenceRefs()).isEmpty();
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void outOfScopeWithSelectedWorkflowIsRejectedWithoutSecondModelCall() {
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of("payment.draft"), true));
        var reply = service.ask(request("오늘 날씨"));
        assertThat(reply.source()).isEqualTo(Source.FIXED);
        assertThat(reply.fallbackReason()).isEqualTo("INVALID_AI_SELECTION");
        assertThat(reply.evidenceRefs()).isEmpty();
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void longAnswerIsStillScreenedForQuantitiesInBoundedTime() {
        String message = "이".repeat(1000) + "다" + "원도".repeat(480) + " 세 번 나누어 지급됩니다.";
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
        var response = org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(2), () -> service.ask(request("초안 저장")));
        assertThat(response.fallbackReason()).isEqualTo("INVALID_AI_OUTPUT");
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
        assertThat(catalog.chunks(guide,"계약번호 입력은 어디서 하나요",List.of("SETTLEMENT")).stream().filter(chunk -> chunk.stepId() != null && chunk.stepId().startsWith("help.")))
            .isNotEmpty().allSatisfy(chunk -> assertThat(chunk.chunkId()).startsWith("contract.register:help."));
        assertThat(catalog.chunks(guide,"zzzzzz",List.of("SETTLEMENT")).stream().filter(chunk -> chunk.stepId() != null && chunk.stepId().startsWith("help."))).isEmpty();
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
    @Test void validCitationsBeyondEightOrRepeatedAreTrimmedInsteadOfFallingBack() {
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class))).thenReturn(new PicoAskService.GuideSelection(List.of("payment.draft","payment.confirm"), false));
        var ids=catalog.definitions().stream().filter(guide -> List.of("payment.draft","payment.confirm").contains(guide.workflowId()))
            .flatMap(guide -> catalog.chunks(guide,List.of("SETTLEMENT")).stream()).map(PicoGuideCatalog.Chunk::chunkId).toList();
        assertThat(ids).hasSizeGreaterThan(8);
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(new PicoAskService.ModelAnswer(Decision.ANSWER_ONLY,"초안을 저장한 뒤 사전검증을 실행하세요.",ids));
        var many=service.ask(request("지급 등록 순서"));
        assertThat(many.source()).isEqualTo(Source.AI);
        assertThat(many.evidenceRefs()).hasSize(8).extracting(Evidence::chunkId).isSubsetOf(ids.subList(0,8));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(new PicoAskService.ModelAnswer(Decision.ANSWER_ONLY,"초안을 저장하세요.",List.of("payment.draft:overview","payment.draft:overview")));
        assertThat(service.ask(request("초안 저장")).evidenceRefs()).extracting(Evidence::chunkId).containsExactly("payment.draft:overview");
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(new PicoAskService.ModelAnswer(Decision.ANSWER_ONLY,"초안을 저장하세요.",List.of("payment.draft:overview","evil:source")));
        assertThat(service.ask(request("초안 저장")).fallbackReason()).as("one forged citation still rejects the answer").isEqualTo("INVALID_AI_OUTPUT");
    }
    @Test void answerInstructionKeepsExactScreenNamesInsteadOfAReplacementWord() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer("1,200% 한도 검증 화면에서 결과를 확인하세요.","payment.draft:overview"));
        assertThat(service.ask(request("한도 결과는 어디서 보나요")).source()).isEqualTo(Source.AI);
        var prompt=ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway).call(prompt.capture(),eq(PicoAskService.ModelAnswer.class));
        assertThat(prompt.getValue().instructions()).contains("화면 이름을 숫자까지 그대로 쓰세요").contains("'수치' 같은 대체어");
    }
    @Test void approvedScreenTitleWithDigitsIsNotAGeneratedNumber() {
        assertThat(catalog.numericScreenTitles()).containsExactly("1,200% 한도 검증");
        // 화면을 가리킬 때만 이름으로 본다(뒤에 화면, 메뉴). 한도나 요율 판단 문장 속 제목은 숫자 검사를 그대로 받는다(#833 리뷰).
        for (var message : List.of("1,200% 한도 검증 화면에서 결과를 확인하세요.", "'1,200% 한도 검증' 화면에서 결과를 확인하세요.", "1,200% 한도 검증 메뉴를 여세요.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("한도 결과는 어디서 보나요")).source()).as(message).isEqualTo(Source.AI);
        }
        for (var message : List.of("1,200% 한도 검증을 한도로 적용하세요.", "1,200% 한도 검증 기준을 넘는 지급은 확정하지 마세요.", "1,200%를 넘으면 확정할 수 없습니다.",
                "1,200% 한도 검증 화면에서 사용률 1,350%를 확인하세요.", "1,200% 한도입니다.", "1,200% 한도 검증의 2단계를 보세요.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("한도 결과는 어디서 보나요")).fallbackReason()).as(message).isEqualTo("INVALID_AI_OUTPUT");
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
    @Test void normalKoreanParticlesAreNotMistakenForNumerals() {
        for (var message : List.of("조회만 할 수 있습니다.", "내용은 조회만 할 수 있습니다.", "통화기호 표시 위치를 조회하세요.", "권한을 확인할 수 있습니다.", "기간을 확인한 다음 조회하세요.", "상세 내용을 확인하세요.",
                "이번 정산월의 지급 목록을 확인하세요.", "이번에는 사전검증을 실행하세요.", "이달의 지급 초안을 저장하세요.", "지급 구분을 확인하세요.", "원천 구분은 상세에서 확인하세요.",
                "저장 전에 한 번 더 확인하세요.", "한번 더 조회해 보세요.", "한 차례 더 조회하세요.", "만일 오류가 있으면 정정하세요.", "일할 계산 근거를 확인하세요.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("조회 방법")).source()).as(message).isEqualTo(Source.AI);
        }
    }
    @Test void generatedKoreanDurationsCountsAndUnicodeNumbersAreRejected() {
        for (var message : List.of("지급금액십만원입니다.", "수수료십만원입니다.", "수수료십퍼센트입니다.", "수수료십프로입니다.", "수수료십%입니다.", "₩오만입니다.", "$오만입니다.", "€오만입니다.", "지급액은십만원입니다.", "수수료는백원입니다.", "총열두달에 걸쳐 지급됩니다.", "약두번 나누어 지급됩니다.", "열두 달에 걸쳐 지급됩니다.", "두 번 나누어 지급됩니다.", "세 차례 지급합니다.", "열한 번 나누어 지급됩니다.", "두 차례 지급합니다.", "삼 일 이내입니다.", "이 주 뒤 지급됩니다.", "스무 개를 선택하세요.", "예순 회에 걸쳐 지급됩니다.", "²원입니다.", "Ⅻ개월입니다.", "½을 지급합니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("지급 방법")).fallbackReason()).as(message).isEqualTo("INVALID_AI_OUTPUT");
        }
    }
    @Test void readersOnlyReceiveStoredPaymentStatusWithoutCorrectionInstructions() {
        for (var role : List.of("COMPLIANCE", "GA_ADMIN")) {
            role(role);
            when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
                    .thenReturn(new PicoAskService.GuideSelection(List.of("payment.find"), false));
            for (var state : CommissionPaymentStatus.values()) {
                when(contexts.read(77L)).thenReturn(new PicoPaymentContextService.Snapshot(state));
                var reply = service.ask(new PicoAskRequest("현재 지급 상태", "TRAN-W01", List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)), "r1", "1.0", List.of()));
                assertThat(reply.source()).isEqualTo(Source.FIXED);
                assertThat(reply.fallbackReason()).isEqualTo("PAYMENT_STATE_READ_ONLY");
                assertThat(reply.message()).contains("상태 조회 안내만").doesNotContain("정정", "저장", "다시 확정");
            }
        }
        verify(gateway,never()).call(any(),eq(PicoAskService.ModelAnswer.class));
    }
    @Test void acceptsOnlyRegisteredGuideStageCountsWithoutAllowingFinancialNumbers() {
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
            .thenReturn(new PicoAskService.GuideSelection(List.of("settlement.journey"), false));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
            .thenReturn(answer("전체 9단계 안내에서 계약부터 최종 확정까지 확인하세요.","settlement.journey:overview"));
        assertThat(service.ask(request("전체 흐름을 안내해주세요")).source()).isEqualTo(Source.AI);
        for (var message : List.of("전체 8단계 안내입니다.", "전체 9단계이며 지급액은 9원입니다.",
                "전체 9,000단계입니다.", "전체 -9단계입니다.", "전체 9.0단계입니다.", "전체 9단계이며 천만 원씩 지급합니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
                .thenReturn(answer(message,"settlement.journey:overview"));
            assertThat(service.ask(request("전체 흐름을 안내해주세요")).fallbackReason()).as(message).isEqualTo("INVALID_AI_OUTPUT");
        }
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
            .thenReturn(new PicoAskService.GuideSelection(List.of("payment.amount.review"), false));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
            .thenReturn(answer("전체 9단계입니다.","payment.amount.review:overview"));
        assertThat(service.ask(request("금액 확인 안내")).fallbackReason()).isEqualTo("INVALID_AI_OUTPUT");
    }
    @Test void normalizesOnlyConsecutiveProcedureListMarkersAndStillRejectsFinancialValues() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
            .thenReturn(answer("1. 지급 대상을 확인하세요.\n2. 근거를 확인하세요.","payment.draft:overview"));
        var reply = service.ask(request("초안 저장 방법"));
        assertThat(reply.source()).isEqualTo(Source.AI);
        assertThat(reply.message()).isEqualTo("지급 대상을 확인하세요.\n근거를 확인하세요.");
        for (var message : List.of("1. 지급액은 50000원입니다.\n2. 저장하세요.",
                "1. 지급액은 오만원입니다.\n2. 저장하세요.", "1. 확인하세요.\n3. 저장하세요.",
                "1. 확인하세요.", "1.25원입니다.\n2.5원입니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("초안 저장 방법")).fallbackReason()).as(message).isEqualTo("INVALID_AI_OUTPUT");
        }
    }
    @Test void normalizesInlineProcedureListMarkersOnlyWhenConsecutive() {
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
            .thenReturn(answer("다음 순서로 진행하세요: 1) 지급 대상을 확인하고 2) 근거를 확인한 후 3) 초안을 저장하세요.","payment.draft:overview"));
        var reply = service.ask(request("초안 저장 순서"));
        assertThat(reply.source()).isEqualTo(Source.AI);
        assertThat(reply.message()).isEqualTo("다음 순서로 진행하세요: 지급 대상을 확인하고 근거를 확인한 후 초안을 저장하세요.");
        for (var message : List.of("먼저 1) 확인하고 3) 저장하세요.", "먼저 1. 지급액은 50000원입니다. 2. 저장하세요.",
                "금액은 1. 2. 3. 4. 5. 6. 7. 8. 9. 입니다.", "요율은 1.5 입니다. 2.5 도 있습니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"payment.draft:overview"));
            assertThat(service.ask(request("초안 저장 순서")).fallbackReason()).as(message).isEqualTo("INVALID_AI_OUTPUT");
        }
    }
    @Test void modelEvidenceOmitsAreaNumbersWhileTheNumberGuardStillRejectsThem() throws Exception {
        role("GA_ADMIN");
        when(gateway.call(any(),eq(PicoAskService.GuideSelection.class)))
                .thenReturn(new PicoAskService.GuideSelection(List.of("clawback.review"), false));
        when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class)))
                .thenReturn(answer("환수 화면의 후보 근거 영역을 확인하세요.","clawback.review:overview"));
        var reply = service.ask(request("환수 후보 근거는 어디에서 보나요?"));
        assertThat(reply.source()).isEqualTo(Source.AI);
        var prompts = ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        verify(gateway,times(2)).call(prompts.capture(),any());
        var evidence = new ObjectMapper().readTree(prompts.getAllValues().getLast().data()).path("evidence").toString();
        assertThat(evidence).contains("후보 근거").doesNotContainPattern("[\\u2460-\\u2473]");
        // 근거에서 뺐을 뿐 검사는 그대로다. 모델이 스스로 쓴 번호와 금액 우회는 계속 거절한다.
        for (var message : List.of("③ 후보 근거 영역을 확인하세요.", "환수액은 ①억 원입니다.")) {
            when(gateway.call(any(),eq(PicoAskService.ModelAnswer.class))).thenReturn(answer(message,"clawback.review:overview"));
            assertThat(service.ask(request("환수 후보 근거는 어디에서 보나요?")).fallbackReason()).as(message).isEqualTo("INVALID_AI_OUTPUT");
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
            .thenReturn(new PicoAskService.GuideSelection(List.of("contract.detail","settlement.journey"), false));
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

