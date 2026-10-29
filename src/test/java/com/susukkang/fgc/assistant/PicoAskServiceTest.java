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
    LlmGateway gateway; PicoAskService service; PicoGuideCatalog catalog;
    @BeforeEach void setup() throws Exception {
        gateway=mock(LlmGateway.class); var mapper=new ObjectMapper(); catalog=new PicoGuideCatalog(mapper); service=new PicoAskService(gateway,catalog,mapper); role("SETTLEMENT");
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
        assertThat(service.ask(new PicoAskRequest(req.question(),req.screenId(),List.of(),"r1","2.0",List.of())).fallbackReason()).isEqualTo("GUIDE_VERSION_MISMATCH");
        assertThat(service.ask(new PicoAskRequest(req.question(),req.screenId(),List.of(new PicoAskRequest.EntityRef(PicoAskRequest.EntityType.PAYMENT,77L)),"r1","1.0",List.of())).fallbackReason()).isEqualTo("ENTITY_STATE_NOT_SUPPORTED");
        assertThat(service.ask(new PicoAskRequest(req.question(),req.screenId(),List.of(),"r1","1.0",List.of(new PicoAskRequest.Turn(PicoAskRequest.Role.USER,"private data")))).fallbackReason()).isEqualTo("HISTORY_NOT_SUPPORTED"); verifyNoInteractions(gateway);
    }
    @Test void financialQuestionUsesProgramCalculationInsteadOfModelArithmetic() {
        assertThat(service.ask(request("이번 달 수수료 얼마야")).fallbackReason()).isEqualTo("CALCULATION_REQUIRED"); verifyNoInteractions(gateway);
    }
    @Test void rejectsForgedSourcesNumbersCompletionClaimsAndExecutableText() {
        for(var bad:List.of(answer("외부로 이동하세요","evil:source"),answer("오만원 50000원입니다","payment.draft:overview"),answer("오만원 지급을 추천합니다","payment.draft:overview"),answer("５만원입니다","payment.draft:overview"),answer("1. 대상을 확인하세요","payment.draft:overview"),answer("javaScript:alert 실행","payment.draft:overview"),answer(java.text.Normalizer.normalize("지급했습니다",java.text.Normalizer.Form.NFD),"payment.draft:overview"),answer("지급했습니다","payment.draft:overview"),answer("https://evil.example 실행","payment.draft:overview"))) {
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
}
