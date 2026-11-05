package com.susukkang.fgc.assistant.service;

import com.susukkang.fgc.assistant.dto.PicoAskRequest;
import com.susukkang.fgc.assistant.dto.PicoAskResponse.ValidatedGuide;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import java.util.*;

/** Resolves registered entry points. Model text never determines a route or completion result. */
final class PicoGuideEntry {
    private PicoGuideEntry() {}

    static String paymentStep(PicoGuideCatalog.Definition definition, boolean savedDraft) {
        // The existing catalog explicitly allows a matching saved DRAFT to bypass saving.
        var first = definition.steps().getFirst();
        if (savedDraft) {
            var next = first.next().stream()
                    .filter(edge -> "CURRENT_DRAFT_ALREADY_SAVED".equals(edge.conditionId())).findFirst();
            if (next.isPresent()) return next.get().stepId();
        }
        return first.stepId();
    }

    static ValidatedGuide resolve(PicoGuideCatalog catalog, PicoAskRequest request,
            List<PicoGuideCatalog.Chunk> evidence, Collection<String> roles,
            PicoPaymentContextService.Snapshot payment) {
        var ids = evidence.stream().map(PicoGuideCatalog.Chunk::workflowId).distinct().toList();
        if (ids.size() != 1) return null;
        var definition = catalog.definitions().stream().filter(guide -> guide.workflowId().equals(ids.getFirst())
                && guide.roles().stream().anyMatch(roles::contains)).findFirst().orElse(null);
        if (definition == null) return null;
        var steps = catalog.allowedSteps(definition, roles);
        if (steps.isEmpty()) return null;
        boolean paymentGuide = catalog.paymentDefinitions().stream().anyMatch(guide -> guide.workflowId().equals(definition.workflowId()));
        if (paymentGuide) {
            if (payment == null || payment.status() != CommissionPaymentStatus.DRAFT || payment.fingerprint() == null) return null;
            String step = paymentStep(definition, true);
            if (steps.stream().noneMatch(value -> value.stepId().equals(step))) return null;
            return new ValidatedGuide(definition.workflowId(), step, definition.guideVersion(),
                    request.entityRefs(), request.clientContextRevision(), payment.fingerprint());
        }
        // These guides navigate registered UI and ask the user to choose any runtime row.
        // No record-specific state is claimed or inferred from an unrelated payment reference.
        var cited = evidence.stream().filter(chunk -> chunk.stepId() != null).map(PicoGuideCatalog.Chunk::stepId).toList();
        var locations = new ArrayList<>(catalog.allowedHelpSteps(definition, roles));
        locations.addAll(steps);
        var step = locations.stream().filter(value -> value.screenId().equals(request.screenId()) && cited.contains(value.stepId()))
                .findFirst().orElse(steps.getFirst());
        return new ValidatedGuide(definition.workflowId(), step.stepId(), definition.guideVersion(),
                List.of(), request.clientContextRevision(), null);
    }
}
