package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.common.code.AiCallPurpose;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.common.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.*;
import java.text.Normalizer;
import static com.susukkang.fgc.assistant.dto.PicoAskResponse.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PicoAskService {
    private final LlmGateway gateway;
    private final PicoGuideCatalog catalog;
    private final ObjectMapper mapper;
    public record ModelAnswer(Decision decision,String message,List<String> evidenceChunkIds) {}
    private static final List<String> VOCABULARY=List.of("초안","저장","지급","확정","검증","금액","예상","한도","귀속","증빙","수정","취소","조회","입력","정정","차액","방법","다음","완료","차이","금지","수수료","작성");
    private static final List<String> UNSAFE_TEXT = List.of(
            "http://", "https://", "www.", "<", ">", "data-guide-target", "queryselector",
            "javascript:", "/api/", "/app/", "저장했습니다", "확정했습니다", "지급했습니다",
            "처리했습니다", "완료되었습니다");
    private static final String MONEY_PREFIXES = "일이삼사오육칠팔구십백천만억조";

    private enum OutputRejection { INVALID_FIELDS, GENERATED_NUMBER, GENERATED_AMOUNT, UNSAFE_TEXT, INVALID_EVIDENCE }

    private static OutputRejection unsafeOutput(String message) {
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (normalized.codePoints().anyMatch(Character::isDigit)) return OutputRejection.GENERATED_NUMBER;
        if (UNSAFE_TEXT.stream().anyMatch(normalized::contains)) return OutputRejection.UNSAFE_TEXT;
        if (List.of("원", "퍼센트", "프로", "배", "할", "푼", "%", "％", "회", "개월", "년").stream()
                .anyMatch(unit -> koreanQuantity(normalized, unit))) return OutputRejection.GENERATED_AMOUNT;
        return null;
    }

    private static boolean koreanQuantity(String text, String unit) {
        int index = text.indexOf(unit);
        while (index >= 0) {
            String suffix = text.substring(index + unit.length());
            boolean boundary = suffix.isEmpty() || !Character.isLetter(suffix.charAt(0))
                    || List.of("까지", "이하", "이상", "입니다", "로", "를", "의", "가", "는", "만").stream().anyMatch(suffix::startsWith);
            int previous = index - 1;
            while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) previous--;
            // 조회 is a lookup word, but a preceding numeral (일조회/일조 회) remains a quantity.
            boolean lookupWord = unit.equals("회") && index > 0 && text.charAt(index - 1) == '조'
                    && (index == 1 || MONEY_PREFIXES.indexOf(text.charAt(index - 2)) < 0);
            if (!lookupWord && boundary && previous >= 0 && numeralEnding(text.substring(0, previous + 1))) return true;
            index = text.indexOf(unit, index + unit.length());
        }
        return false;
    }
    private static boolean numeralEnding(String prefix) {
        return MONEY_PREFIXES.indexOf(prefix.charAt(prefix.length() - 1)) >= 0
                || List.of("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "스물", "서른", "마흔", "쉰").stream().anyMatch(prefix::endsWith);
    }

    @PreAuthorize(Roles.ANY_ROLE)
    public PicoAskResponse ask(PicoAskRequest request) {
        PicoAskResponse contextError = contextError(request);
        if (contextError != null) return contextError;
        var terms = terms(request.question());
        boolean canProcess = canProcess();
        PicoAskResponse intentError = intentError(request, terms, canProcess);
        if (intentError != null) return intentError;
        var candidates=catalog.search(terms,canProcess);
        if (candidates.isEmpty()) return fixed(request,Decision.ASK_CLARIFICATION,"금액 확인·초안 저장·사전검증 중 어떤 업무가 필요한가요?",List.of(),"WORKFLOW_REQUIRED");
        if (candidates.size()>1 && catalog.score(candidates.get(0),terms)==catalog.score(candidates.get(1),terms)) return fixed(request,Decision.ASK_CLARIFICATION,"관련된 업무가 여러 개예요. 금액 확인·초안 저장·사전검증 중 원하는 목적을 알려주세요.",List.of(),"AMBIGUOUS_WORKFLOW");
        var selected=candidates.getFirst();
        var chunks=catalog.chunks(selected);
        if (terms.contains("금액") && (request.question().contains("얼마") || request.question().contains("계산"))) return fixed(request,Decision.ANSWER_ONLY,"실제 금액은 프로그램의 현재 지급 건 계산 조회에서 확인해주세요. 이 답변은 금액을 계산하거나 지급을 승인하지 않습니다.",chunks.subList(0,1),"CALCULATION_REQUIRED");
        try {
            // Never send raw question, history, entity IDs, amounts, names or current business state.
            String data=mapper.writeValueAsString(Map.of("intentTerms",terms,"approvedGuide",selected.title(),"evidence",chunks));
            ModelAnswer output=gateway.call(new LlmGateway.LlmRequest(AiCallPurpose.PICO_EXPLAIN,
                    "승인된 지급 업무 가이드만 근거로 한국어 설명을 작성하세요. intentTerms는 질문에서 허용된 업무 단어만 추출한 자료입니다. 모호하면 ASK_CLARIFICATION, 범위 밖이면 UNSUPPORTED입니다. 현재 업무 상태/개인정보/금액은 제공되지 않았습니다. 숫자·금액·요율을 만들지 말고 저장/확정/처리를 완료했다고 말하지 마세요. URL/선택자/스크립트/실행 명령을 반환하지 마세요. ANSWER_ONLY에는 받은 evidence의 chunkId를 하나 이상 인용하세요. message는 목록/순서 번호 없이 두세 문장으로 작성하고 근거 ID·버전·숫자를 본문에 반복하지 마세요. 근거 ID는 evidenceChunkIds 배열에만 넣으세요. 초안 저장은 지급 확정이 아니라는 구분을 유지하고 직접 실행하지 않습니다.",data,null,null),ModelAnswer.class);
            OutputRejection rejection = rejection(output, chunks);
            if (rejection != null) {
                // Bounded enum only: never log the model message, question, IDs or credentials.
                log.warn("Pico answer rejected: {}", rejection);
                return fallback(request,chunks,"INVALID_AI_OUTPUT");
            }
            var references=chunks.stream().filter(c->output.evidenceChunkIds().contains(c.chunkId())).toList();
            return response(request,output.decision(),output.message(),references,Source.AI,null);
        } catch (FgcBusinessException error) {
            if (error.getErrorCode()!=FgcErrorCode.AI_001) throw error;
            return fallback(request,chunks,"AI_UNAVAILABLE");
        } catch (JsonProcessingException error) { return fallback(request,chunks,"INPUT_ENCODING_ERROR"); }
    }
    private PicoAskResponse contextError(PicoAskRequest request) {
        if (!Set.of("TRAN-W02", "CMN-C04").contains(request.screenId()))
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "screenId", Map.of("field", "screenId"), "승인된 질문 화면이 아닙니다.");
        if (!"1.0".equals(request.guideVersion()))
            throw new FgcBusinessException(FgcErrorCode.IMPL_GUIDE_VERSION_MISMATCH);
        if (!request.entityRefs().isEmpty()) return fixed(request,Decision.ANSWER_ONLY,"이 연결은 지급 업무의 일반 절차를 설명합니다. 실제 대상의 금액과 상태는 기존 계산 조회에서 확인해주세요.",List.of(),"ENTITY_STATE_NOT_SUPPORTED");
        if (request.conversation()!=null && !request.conversation().isEmpty()) return fixed(request,Decision.ASK_CLARIFICATION,"이번 연결은 현재 질문을 기준으로 안내합니다. 원하는 지급 업무를 질문에 다시 적어주세요.",List.of(),"HISTORY_NOT_SUPPORTED");
        return null;
    }
    private PicoAskResponse intentError(PicoAskRequest request, List<String> terms, boolean canProcess) {
        if (terms.isEmpty()) return fixed(request,Decision.UNSUPPORTED,"지급 금액 확인·초안 작성·사전검증과 확정 절차를 안내할 수 있어요. 원하는 업무를 구체적으로 적어주세요.",List.of(),"NO_SUPPORTED_WORKFLOW");
        if (terms.stream().anyMatch(Set.of("수정","정정","취소")::contains)) return fixed(request,Decision.ASK_CLARIFICATION,"작성 중인 초안을 수정하려는 건가요, 이미 확정된 지급을 정정하려는 건가요? 상태에 맞는 기존 업무 화면을 먼저 확인해주세요.",List.of(),"PAYMENT_STATE_REQUIRED");
        if (!canProcess && terms.stream().anyMatch(Set.of("저장","확정","취소","정정","작성")::contains)) return fixed(request,Decision.UNSUPPORTED,"현재 계정은 조회 안내만 사용할 수 있어요. 지급 처리 업무는 정산 담당자에게 요청해주세요.",List.of(),"PERMISSION_DENIED");
        return null;
    }
    public static List<String> terms(String question) {
        String normalized=question.replaceAll("\\s+","");
        var result=new ArrayList<String>();
        for(var term:VOCABULARY) if(normalized.contains(term)) result.add(term);
        if((normalized.contains("얼마") || normalized.contains("계산")) && !result.contains("금액")) result.add("금액");
        if(normalized.contains("잘못") && !result.contains("수정")) result.add("수정");
        if(normalized.contains("임시") && !result.contains("초안")) result.add("초안");
        if(normalized.contains("끝") && !result.contains("완료")) result.add("완료");
        if((normalized.contains("말고") || normalized.contains("하지마") || normalized.contains("없이")) && !result.contains("금지")) result.add("금지");
        return List.copyOf(result);
    }
    private OutputRejection rejection(ModelAnswer output, List<PicoGuideCatalog.Chunk> chunks) {
        if (output == null || output.decision() == null || output.message() == null
                || output.message().isBlank() || output.message().length() > 2000
                || output.evidenceChunkIds() == null || output.evidenceChunkIds().size() > 8)
            return OutputRejection.INVALID_FIELDS;
        OutputRejection unsafe = unsafeOutput(output.message());
        if (unsafe != null) return unsafe;
        var allowed = chunks.stream().map(PicoGuideCatalog.Chunk::chunkId).toList();
        if (output.decision() == Decision.ANSWER_ONLY && output.evidenceChunkIds().isEmpty())
            return OutputRejection.INVALID_EVIDENCE;
        if (output.evidenceChunkIds().stream().anyMatch(id -> id == null || !allowed.contains(id)))
            return OutputRejection.INVALID_EVIDENCE;
        return null;
    }
    private PicoAskResponse fallback(PicoAskRequest request,List<PicoGuideCatalog.Chunk> chunks,String reason) {
        var used=chunks.subList(0,Math.min(2,chunks.size()));
        return fixed(request,Decision.ANSWER_ONLY,String.join(" ",used.stream().map(PicoGuideCatalog.Chunk::content).toList()),used,reason);
    }
    private PicoAskResponse fixed(PicoAskRequest request,Decision decision,String message,List<PicoGuideCatalog.Chunk> chunks,String reason) { return response(request,decision,message,chunks,Source.FIXED,reason); }
    private PicoAskResponse response(PicoAskRequest request,Decision decision,String message,List<PicoGuideCatalog.Chunk> chunks,Source source,String reason) {
        return new PicoAskResponse(decision,message,"1.0",request.clientContextRevision(),chunks.stream().map(c->new Evidence(c.chunkId(),c.workflowId(),c.stepId(),c.title())).toList(),null,source,reason);
    }
    private boolean canProcess() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_SYSTEM_ADMIN","ROLE_SETTLEMENT").contains(a.getAuthority()));
    }
}
