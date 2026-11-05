package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.web.ApiResponse;
import com.susukkang.fgc.validation.dto.ValidationRestartRequest;
import com.susukkang.fgc.validation.service.ValidationRestartService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/validation-runs")
@RequiredArgsConstructor
public class ValidationRestartController {
  private final ValidationRestartService service;

  @GetMapping("/{id}/restartability")
  @PreAuthorize(Roles.ANY_ROLE)
  public ApiResponse<ValidationRestartService.Restartability> eligibility(@PathVariable Long id) {
    return ApiResponse.success(service.eligibility(id));
  }

  @GetMapping("/{id}/retries")
  @PreAuthorize(Roles.ANY_ROLE)
  public ApiResponse<List<ValidationRestartService.Retry>> history(@PathVariable Long id) {
    return ApiResponse.success(service.history(id));
  }

  @PostMapping("/{id}/restart")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize(Roles.CAN_PROCESS)
  public ApiResponse<ValidationRestartService.Accepted> restart(
      @PathVariable Long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ValidationRestartRequest request,
      @AuthenticationPrincipal FgcUserDetails user) {
    return ApiResponse.success(service.restart(id, user.getUserId(), key, request));
  }
}
