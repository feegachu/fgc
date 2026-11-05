package com.susukkang.fgc.assistant.controller;
import com.susukkang.fgc.assistant.service.AssistantPrecheckProofService;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.common.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant/prechecks")
@PreAuthorize(Roles.CAN_PROCESS)
public class AssistantPrecheckProofController {
    private final AssistantPrecheckProofService service;
    public record VerifyRequest(@NotBlank @Pattern(regexp="[1-9]\\d{0,18}") String paymentId,@NotBlank @Size(max=100) String precheckToken) {}
    @PostMapping("/revalidate")
    @Operation(summary="현재 지급의 실제 사전검증 증표 확인",description="계산 증표로 대체할 수 없습니다. 금융 상태를 변경하지 않습니다.")
    public ApiResponse<AssistantPrecheckProofService.Verification> verify(@RequestBody @Valid VerifyRequest request) {
        long id;try{id=Long.parseLong(request.paymentId());}catch(NumberFormatException e){throw new com.susukkang.fgc.common.exception.FgcBusinessException(com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_002);}
        return ApiResponse.success(service.verify(id,request.precheckToken()));
    }
}
