package com.susukkang.fgc.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CommissionPaymentBatchRequest(
        @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid CommissionPaymentCreateRequest> payments
) {}
