package com.susukkang.fgc.document.controller;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.document.service.GeneratedDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IF-API-67A 생성 PDF 판별 다운로드. 내부 4개 역할만 허용하고 AGENT는 직접 URL로도 받을 수 없다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/documents")
@PreAuthorize(Roles.ANY_ROLE)
public class GeneratedDocumentController {
    private final GeneratedDocumentService service;

    @GetMapping(value = "/{documentNo}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(operationId = "downloadGeneratedDocument", summary = "IF-API-67A 생성 PDF 판 다운로드",
            description = "문서번호와 판이 모두 필요합니다. 발행 때 기록한 sha256과 원본이 다르면 내려주지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF 파일",
                    content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 문서·판")
    })
    public ResponseEntity<byte[]> download(@PathVariable String documentNo, @RequestParam int version) {
        var file = service.download(documentNo, version);
        String filename = file.documentNo() + "-v" + file.version() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.content());
    }
}
