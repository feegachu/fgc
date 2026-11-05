package com.susukkang.fgc.assistant.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;

@Component
public class PicoGuideCatalog {
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Definition(String workflowId,String guideVersion,String title,String description,String capability,List<String> keywords,List<Step> steps,boolean enabled,List<String> roles,List<String> requiredSlots) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Step(String stepId,String screenId,String targetId,String title,String instruction,String completionRuleId,List<Next> next,List<String> roles) {}
    public record Next(String conditionId,String stepId) {}
    public record Chunk(String chunkId,String workflowId,String stepId,String title,String content) {}
    private static final Set<String> ROLES = Set.of("SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE", "AGENT");
    private final List<Definition> definitions;
    private final Set<String> screenIds;
    private final List<String> numericScreenTitles;
    private final Set<String> paymentWorkflowIds;
    private final Map<String, List<Step>> help;
    public record HelpDefinition(String workflowId, List<Step> steps) {}
    public PicoGuideCatalog(ObjectMapper mapper) throws IOException {
        try(var stream=new ClassPathResource("ai/pico/screens.generated.json").getInputStream()) {
            List<Map<String,Object>> screens = mapper.readValue(stream,new TypeReference<>(){});
            var ids = new HashSet<String>();
            var numeric = new ArrayList<String>();
            for (var screen : screens) {
                ids.add((String) screen.get("id"));
                if (screen.get("title") instanceof String title && title.codePoints().anyMatch(Character::isDigit))
                    numeric.add(Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
            }
            ids.add("CMN-C04");
            screenIds = Set.copyOf(ids);
            numericScreenTitles = List.copyOf(numeric);
        }
        Set<String> featureIds;
        try(var stream=new ClassPathResource("ai/pico/feature-targets.generated.json").getInputStream()) {
            List<Map<String,Object>> features = mapper.readValue(stream,new TypeReference<>(){});
            featureIds = features.stream().map(feature -> (String) feature.get("id")).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        var loaded = new ArrayList<Definition>();
        for (var file : List.of("payment-guides.v1.json", "workflow-guides.v1.json")) {
            try(var stream=new ClassPathResource("ai/pico/" + file).getInputStream()) {
                loaded.addAll(mapper.readValue(stream,new TypeReference<List<Definition>>(){}));
            }
        }
        definitions = loaded.stream().filter(Definition::enabled).toList();
        try(var stream=new ClassPathResource("ai/pico/payment-guides.v1.json").getInputStream()) {
            List<Definition> payments = mapper.readValue(stream,new TypeReference<>(){});
            paymentWorkflowIds = payments.stream().map(Definition::workflowId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        try(var stream=new ClassPathResource("ai/pico/workflow-help.generated.json").getInputStream()) {
            List<HelpDefinition> locations = mapper.readValue(stream,new TypeReference<>(){});
            var map = new LinkedHashMap<String, List<Step>>();
            for (var location : locations) {
                if (loaded.stream().noneMatch(guide -> guide.workflowId().equals(location.workflowId())) || map.containsKey(location.workflowId()) || location.steps() == null) throw new IllegalStateException("Unknown help workflow");
                if (definitions.stream().noneMatch(guide -> guide.workflowId().equals(location.workflowId()))) continue;
                var ids = new HashSet<String>();
                for (var step : location.steps()) if (step.stepId() == null || !step.stepId().matches("help\\.[a-f0-9]{32}") || !ids.add(step.stepId()) || !featureIds.contains(step.targetId()) || !screenIds.contains(step.screenId()) || !"USER_REVIEWED".equals(step.completionRuleId()) || step.next() == null || !step.next().isEmpty() || !validRoles(step.roles())) throw new IllegalStateException("Invalid UI help");
                map.put(location.workflowId(), List.copyOf(location.steps()));
            }
            help = Map.copyOf(map);
        }
        if (loaded.isEmpty() || loaded.stream().map(Definition::workflowId).distinct().count()!=loaded.size()) throw new IllegalStateException("Invalid guide registry");
        for (var guide:definitions) {
            if (!guide.workflowId().matches("[a-z][a-z0-9.]+") || !"1.0".equals(guide.guideVersion()) || !Set.of("READ","PROCESS").contains(guide.capability()) || guide.steps()==null || guide.steps().isEmpty()) throw new IllegalStateException("Invalid guide definition");
            var expectedRoles = "READ".equals(guide.capability()) ? Set.of("SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE") : Set.of("SYSTEM_ADMIN", "SETTLEMENT");
            if (guide.roles() == null || guide.roles().isEmpty() || !Set.of("SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE", "AGENT").containsAll(guide.roles()) || (guide.workflowId().startsWith("payment.") && Set.of("payment.amount.review", "payment.draft", "payment.confirm").contains(guide.workflowId()) && !new HashSet<>(guide.roles()).equals(expectedRoles))) throw new IllegalStateException("Invalid guide roles");
            var ids=new HashSet<String>();
            for(var step:guide.steps()) if (!validRoles(step.roles()) || !ids.add(step.stepId()) || !screenIds.contains(step.screenId()) ||
                    !(featureIds.contains(step.targetId()) || (step.stepId().equals(step.targetId()) && "TRAN-W02".equals(step.screenId()) && Set.of("payment.scope","payment.amount","payment.attributions","payment.evidence","payment.save","payment.precheck","payment.precheck.result","payment.confirm").contains(step.targetId())))) throw new IllegalStateException("Invalid guide target");
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
    public List<Definition> paymentDefinitions() { return definitions.stream().filter(guide -> paymentWorkflowIds.contains(guide.workflowId())).toList(); }
    public boolean supportsScreen(String screenId) { return screenIds.contains(screenId); }
    /** Approved screen titles that contain digits, such as "1,200% 한도 검증". Naming the screen is not a generated number. */
    public List<String> numericScreenTitles() { return numericScreenTitles; }
    /** Corpus-derived n-grams rank source labels; no fixed vocabulary or question dispatch. */
    private static Set<String> grams(String text) {
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        var result = new HashSet<String>();
        for (int size = 2; size <= 3; size++) for (int index = 0; index + size <= normalized.length(); index++) result.add(normalized.substring(index,index+size));
        return result;
    }
    private static boolean validRoles(List<String> roles) { return roles != null && !roles.isEmpty() && ROLES.containsAll(roles); }
    public List<Step> allowedSteps(Definition guide, Collection<String> roles) {
        return guide.steps().stream().filter(step -> step.roles().stream().anyMatch(roles::contains)).toList();
    }
    public List<Step> allowedHelpSteps(Definition guide, Collection<String> roles) {
        return help.getOrDefault(guide.workflowId(), List.of()).stream()
                .filter(step -> step.roles().stream().anyMatch(roles::contains)).toList();
    }
    public List<Chunk> chunks(Definition guide, String query, Collection<String> roles) {
        var chunks = new ArrayList<>(chunks(guide, roles));
        var terms = grams(query);
        var ranked = help.getOrDefault(guide.workflowId(),List.of()).stream()
            .filter(step -> step.roles().stream().anyMatch(roles::contains))
            .map(step -> Map.entry(step, grams(step.title()).stream().filter(terms::contains).count()))
            .filter(entry -> entry.getValue() > 0)
            .sorted(Comparator.<Map.Entry<Step,Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(entry -> entry.getKey().stepId()))
            .limit(8).toList();
        for (var entry : ranked) {
            var step = entry.getKey();
            chunks.add(new Chunk(guide.workflowId()+":"+step.stepId(),guide.workflowId(),step.stepId(),step.title(),step.instruction()));
        }
        return List.copyOf(chunks);
    }
    public List<Chunk> chunks(Definition guide, Collection<String> roles) {
        var steps = allowedSteps(guide, roles);
        if (steps.isEmpty()) return List.of();
        var chunks=new ArrayList<Chunk>();
        // A shared overview must not reintroduce instructions removed by the step permission filter.
        String overview = steps.stream().map(Step::title).collect(java.util.stream.Collectors.joining(" · "));
        chunks.add(new Chunk(guide.workflowId()+":overview",guide.workflowId(),null,guide.title(),overview));
        for(var step:steps) chunks.add(new Chunk(guide.workflowId()+":"+step.stepId(),guide.workflowId(),step.stepId(),step.title(),step.instruction()));
        return List.copyOf(chunks);
    }
}
