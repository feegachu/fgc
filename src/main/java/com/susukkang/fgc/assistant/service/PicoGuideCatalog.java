package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

@Component
public class PicoGuideCatalog {
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Definition(String workflowId,String guideVersion,String title,String description,String capability,List<String> keywords,List<Step> steps,boolean enabled,List<String> roles) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Step(String stepId,String screenId,String targetId,String title,String instruction,String completionRuleId,List<Next> next) {}
    public record Next(String conditionId,String stepId) {}
    public record Chunk(String chunkId,String workflowId,String stepId,String title,String content) {}
    private final List<Definition> definitions;
    public PicoGuideCatalog(ObjectMapper mapper) throws IOException {
        try(var stream=new ClassPathResource("ai/pico/payment-guides.v1.json").getInputStream()) {
            definitions=List.copyOf(mapper.readValue(stream,new TypeReference<List<Definition>>(){}));
        }
        if (definitions.size()!=3 || definitions.stream().map(Definition::workflowId).distinct().count()!=3) throw new IllegalStateException("Invalid guide registry");
        for (var guide:definitions) {
            if (!Set.of("payment.amount.review","payment.draft","payment.confirm").contains(guide.workflowId()) || !"1.0".equals(guide.guideVersion()) || !guide.enabled() || !Set.of("READ","PROCESS").contains(guide.capability()) || guide.keywords()==null || guide.steps()==null || guide.steps().isEmpty()) throw new IllegalStateException("Invalid guide definition");
            var expectedRoles = "READ".equals(guide.capability()) ? Set.of("SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE") : Set.of("SYSTEM_ADMIN", "SETTLEMENT");
            if (guide.roles() == null || !new HashSet<>(guide.roles()).equals(expectedRoles)) throw new IllegalStateException("Invalid guide roles");
            var ids=new HashSet<String>();
            for(var step:guide.steps()) if (!ids.add(step.stepId()) || !step.stepId().equals(step.targetId()) || !Set.of("payment.scope","payment.amount","payment.attributions","payment.evidence","payment.save","payment.precheck","payment.precheck.result","payment.confirm").contains(step.targetId()) || !"TRAN-W02".equals(step.screenId())) throw new IllegalStateException("Invalid guide target");
            for (var step : guide.steps()) {
                if (step.next() == null) throw new IllegalStateException("Missing guide transitions");
                boolean conditionalSave = "payment.confirm".equals(guide.workflowId()) && "payment.scope".equals(step.stepId());
                if (conditionalSave) {
                    var expected = Set.of(new Next("CURRENT_DRAFT_ALREADY_SAVED", "payment.precheck"), new Next("DRAFT_SAVE_REQUIRED", "payment.save"));
                    if (step.next().size() != 2 || !new HashSet<>(step.next()).equals(expected)) throw new IllegalStateException("Invalid draft save branches");
                }
                for (var edge : step.next()) {
                    if ((!conditionalSave && !Objects.equals(edge.conditionId(), step.completionRuleId())) || !ids.contains(edge.stepId())) throw new IllegalStateException("Invalid guide transition");
                }
            }
        }
    }
    public List<Definition> definitions() { return definitions; }
    public List<Definition> search(List<String> terms,boolean canProcess) {
        return definitions.stream().filter(d->canProcess || "READ".equals(d.capability()))
                .filter(d->score(d,terms)>0).sorted(Comparator.<Definition>comparingInt(d->score(d,terms)).reversed().thenComparing(Definition::workflowId)).limit(5).toList();
    }
    public int score(Definition guide,List<String> terms) { return (int)guide.keywords().stream().filter(terms::contains).count(); }
    public List<Chunk> chunks(Definition guide) {
        var chunks=new ArrayList<Chunk>();
        chunks.add(new Chunk(guide.workflowId()+":overview",guide.workflowId(),null,guide.title(),guide.description()));
        for(var step:guide.steps()) chunks.add(new Chunk(guide.workflowId()+":"+step.stepId(),guide.workflowId(),step.stepId(),step.title(),step.instruction()));
        return List.copyOf(chunks.subList(0,Math.min(8,chunks.size())));
    }
}
