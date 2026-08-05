package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;

public interface CapValidator {
    CapValidationResult validate(CapValidationRequest request);
}
