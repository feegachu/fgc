package com.susukkang.fgc.transaction.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;
import org.hibernate.query.NativeQuery;

/** Request-local read projection. CTEs shadow sources only inside a SELECT; no INSERT/UPDATE/rollback trick. */
public record PaymentCandidateProjection(long paymentId,String paymentJson,String attributionJson) {
    public String sql(String original) {
        return """
            WITH assistant_ct AS (
                SELECT * FROM fgc.commission_transaction WHERE commission_transaction_id <> :candidatePaymentId
                UNION ALL SELECT * FROM jsonb_populate_record(NULL::fgc.commission_transaction, CAST(:candidatePayment AS jsonb))
            ), assistant_ta AS (
                SELECT * FROM fgc.transaction_attribution WHERE commission_transaction_id <> :candidatePaymentId
                UNION ALL SELECT * FROM jsonb_populate_recordset(NULL::fgc.transaction_attribution, CAST(:candidateAttributions AS jsonb))
            )
            """ + original.replace("fgc.commission_transaction","assistant_ct").replace("fgc.transaction_attribution","assistant_ta");
    }
    public void bind(NativeQuery<?> query) {query.setParameter("candidatePaymentId",paymentId);query.setParameter("candidatePayment",paymentJson);query.setParameter("candidateAttributions",attributionJson);}
    public static PaymentCandidateProjection from(long id,CommissionPaymentCommand payment,List<CommissionPaymentAttributionCommand> attributions,ObjectMapper mapper) {
        ObjectNode header=mapper.createObjectNode();header.put("commission_transaction_id",id);header.put("status","DRAFT");
        header.set("amount",mapper.valueToTree(payment.getAmount()));header.set("recipient_agent_id",mapper.valueToTree(payment.getAgentId()));
        header.set("commission_item_id",mapper.valueToTree(payment.getCommissionItemId()));header.set("policy_version_id",mapper.valueToTree(payment.getPolicyVersionId()));
        header.set("payment_stage",mapper.valueToTree(payment.getPaymentStage()));header.set("cashflow_type",mapper.valueToTree(payment.getCashflowType()));
        header.set("due_date",mapper.valueToTree(payment.getDueDate()));header.set("settlement_month",mapper.valueToTree(payment.getSettlementMonth()));header.set("evidence_ref",mapper.valueToTree(payment.getEvidenceRef()));
        ArrayNode rows=mapper.createArrayNode();long index=0;
        for(var line:attributions) {
            ObjectNode row=rows.addObject();row.put("transaction_attribution_id",--index);row.put("commission_transaction_id",id);
            row.set("attribution_seq",mapper.valueToTree(line.getAttributionSequence()));row.set("contract_id",mapper.valueToTree(line.getContractId()));
            row.set("attributed_amount",mapper.valueToTree(line.getAmount()));row.set("attribution_date",mapper.valueToTree(line.getAttributionDate()));row.set("attribution_month",mapper.valueToTree(line.getAttributionMonth()));
            row.set("inclusion_status_snapshot",mapper.valueToTree(line.getInclusionDecisionStatus()));row.set("exclusion_type_snapshot",mapper.valueToTree(line.getExclusionType()));
            row.set("attribution_method",mapper.valueToTree(line.getAttributionMethod()));row.set("evidence_ref",mapper.valueToTree(line.getEvidenceRef()));
            try {row.set("allocation_basis_snapshot",line.getAllocationBasisJson()==null?mapper.nullNode():mapper.readTree(line.getAllocationBasisJson()));}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid normalized attribution basis",e);}
        }
        return new PaymentCandidateProjection(id,header.toString(),rows.toString());
    }
}
