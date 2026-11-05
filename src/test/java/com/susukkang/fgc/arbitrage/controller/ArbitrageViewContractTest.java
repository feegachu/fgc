package com.susukkang.fgc.arbitrage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.common.web.PageResponse;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ArbitrageController.class)
@Import({
  SecurityConfig.class,
  GlobalExceptionHandler.class,
  org.springdoc.core.configuration.SpringDocConfiguration.class,
  org.springdoc.core.properties.SpringDocConfigProperties.class,
  org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class
})
class ArbitrageViewContractTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockitoBean ArbitrageService service;
  @MockitoBean FgcMessageResolver messages;
  @MockitoBean ConstraintErrorCodeResolver constraints;

  @Test
  void authenticatedReadPreservesStoredBasisAndLegacyNullWithoutChangingAmounts() throws Exception {
    for (String basis :
        Arrays.asList(null, "REMAINING_V1", "UNRESOLVED_UPPER_BOUND_V1", "FUTURE_BASIS_V2")) {
      var row =
          ArbitrageCheckView.builder()
              .plannedCommissionAmount(new BigDecimal("50000"))
              .plannedCommissionBasis(basis)
              .build();
      when(service.selectByCondition(any(), eq(1), eq(20)))
          .thenReturn(
              new ArbitrageSearchResponse(
                  null, PageResponse.of(List.of(row), 1, 20, 1, "arbitrageCheckId,desc")));
      String json =
          mvc.perform(
                  get("/api/v1/arbitrage-checks")
                      .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPLIANCE"))))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.items.content[0].plannedCommissionAmount").value(50000))
              .andReturn()
              .getResponse()
              .getContentAsString();
      var actual = mapper.readTree(json).at("/data/items/content/0/plannedCommissionBasis");
      if (basis == null) assertThat(actual.isNull()).isTrue();
      else assertThat(actual.asText()).isEqualTo(basis);
    }
  }

  @Test
  void generatedSchemaContainsOptionalStoredBasis() throws Exception {
    String json =
        mvc.perform(
                get("/v3/api-docs")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var generated = mapper.readTree(json).at("/components/schemas/ArbitrageCheckView");
    assertThat(generated.path("properties").has("plannedCommissionBasis")).isTrue();
    assertThat(generated.path("required").toString()).doesNotContain("plannedCommissionBasis");
    if ("true".equals(System.getenv("FGC_EXPORT_ARBITRAGE_OPENAPI"))) {
      Path file = Path.of("frontend/openapi/schema.json");
      ObjectNode existing = (ObjectNode) mapper.readTree(Files.readString(file));
      ((ObjectNode) existing.at("/components/schemas")).set("ArbitrageCheckView", generated);
      Files.writeString(
          file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(existing) + "\n");
    }
  }
}
