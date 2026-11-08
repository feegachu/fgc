package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;

public record ExceptionSourceRecheckResponse(CapCheckBasisResponse capBasis, boolean resolved,
                                            String message, ExceptionActionResponse action) {}
