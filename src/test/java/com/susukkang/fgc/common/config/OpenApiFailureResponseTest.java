package com.susukkang.fgc.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 4xx·5xx 문서가 성공 봉투 타입이 아니라 실패 봉투(data null)로 나오는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiFailureResponseTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @ParameterizedTest
    @CsvSource({"/api/v1/policies,post", "/api/v1/policies/{policyVersionId},put"})
    void policyConflictSnapshotMatchesLiveFailureEnvelope(String path, String method) throws Exception {
        JsonNode live = json.readTree(mvc.perform(get("/v3/api-docs").with(user("schema-check").roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode snapshot = json.readTree(Files.readString(Path.of("frontend/openapi/schema.json")));
        JsonNode response = live.path("paths").path(path).path(method).path("responses").path("422");

        assertThat(response.path("content").size()).isEqualTo(1);
        assertThat(response.path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiFailureResponse");
        assertThat(snapshot.path("paths").path(path).path(method).path("responses").path("422"))
                .as("%s %s 422 snapshot matches authenticated server export", method, path).isEqualTo(response);
        for (String name : List.of("ApiFailureResponse", "ApiError")) {
            assertThat(snapshot.path("components").path("schemas").path(name))
                    .as("%s failure component matches server", name)
                    .isEqualTo(live.path("components").path("schemas").path(name));
        }
    }

    @Test
    void errorResponsesUseFailureEnvelope() throws Exception {
        JsonNode root = json.readTree(mvc.perform(get("/v3/api-docs").with(user("schema-check").roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        List<String> errorResponses = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        List<String> withoutSuccess = new ArrayList<>();
        root.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            // 오류 코드만 적은 @ApiResponse는 springdoc이 200을 빼먹게 만든다.
            if (operation.getValue().path("responses").properties().stream().noneMatch(r -> r.getKey().startsWith("2"))) {
                withoutSuccess.add(operation.getKey() + " " + path.getKey());
            }
            operation.getValue().path("responses").properties().forEach(response -> {
                if (!response.getKey().matches("[45]\\d\\d")) return;
                String name = operation.getKey() + " " + path.getKey() + " " + response.getKey();
                errorResponses.add(name);
                JsonNode content = response.getValue().path("content");
                // 토큰 없는 401은 본문이 없으므로 본문 타입을 두지 않는다.
                if (response.getKey().equals("401")) {
                    if (!content.isMissingNode()) mismatched.add(name);
                    return;
                }
                if (content.size() != 1 || !content.path("application/json").path("schema").path("$ref").asText()
                        .equals("#/components/schemas/ApiFailureResponse")) {
                    mismatched.add(name);
                }
            });
        }));
        assertThat(errorResponses).isNotEmpty();
        assertThat(mismatched).isEmpty();
        assertThat(withoutSuccess).isEmpty();

        JsonNode schemas = root.path("components").path("schemas");
        JsonNode failure = schemas.path("ApiFailureResponse");
        assertThat(failure.path("properties").path("data").path("type").toString()).contains("null");
        assertThat(failure.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("data", "error", "requestId");
        assertThat(schemas.path("ApiError").path("properties").path("field").path("type").toString())
                .contains("string").contains("null");
        assertThat(schemas.path("ApiError").path("properties").path("detail").path("type").toString())
                .contains("string").contains("null");
    }
}
