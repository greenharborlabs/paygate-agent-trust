package com.greenharborlabs.paygate.reference.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.PaygateReferenceApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = PaygateReferenceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiDocumentationTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @Test
  @SuppressWarnings("unchecked")
  void openApiJsonDocumentsPublicEndpointsAndPaymentChallenge() throws Exception {
    Map<String, Object> spec = getJson("/v3/api-docs");

    Map<String, Object> info = (Map<String, Object>) spec.get("info");
    assertThat(info)
        .containsEntry("title", "Paygate Agent Trust API")
        .containsEntry("version", "0.1.2");

    assertThat(spec.get("servers").toString()).contains("https://paygate-agent-trust.fly.dev");

    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    assertThat(paths.keySet())
        .contains(
            "/healthz",
            "/",
            "/api/v1/catalog",
            "/api/v1/verification/keys",
            "/api/v1/trust/verify",
            "/api/v1/trust/quote",
            "/api/v1/trust/report");

    Map<String, Object> reportPath = (Map<String, Object>) paths.get("/api/v1/trust/report");
    Map<String, Object> reportGet = (Map<String, Object>) reportPath.get("get");
    Map<String, Object> responses = (Map<String, Object>) reportGet.get("responses");
    assertThat(responses.keySet()).contains("200", "400", "402", "503");
    assertThat(String.valueOf(((Map<String, Object>) responses.get("402")).get("description")))
        .contains("Payment required");

    Map<String, Object> components = (Map<String, Object>) spec.get("components");
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    assertThat(schemas.keySet())
        .contains("DiscoveryResponse", "TrustReportResponse", "QuoteResponse", "ApiProblemResponse");

    Map<String, Object> rootPath = (Map<String, Object>) paths.get("/");
    Map<String, Object> rootGet = (Map<String, Object>) rootPath.get("get");
    Map<String, Object> rootResponses = (Map<String, Object>) rootGet.get("responses");
    Map<String, Object> rootOk = (Map<String, Object>) rootResponses.get("200");
    Map<String, Object> rootContent = (Map<String, Object>) rootOk.get("content");
    Map<String, Object> rootJson = (Map<String, Object>) rootContent.get("application/json");
    assertThat(rootJson.toString()).contains("#/components/schemas/DiscoveryResponse");
  }

  @Test
  void openApiYamlAndSwaggerUiAreExposed() throws Exception {
    assertThat(get("/v3/api-docs.yaml").statusCode()).isEqualTo(200);
    assertThat(get("/swagger-ui.html").statusCode()).isIn(200, 302, 307, 308);
  }

  @Test
  @SuppressWarnings("unchecked")
  void rootProvidesUnauthenticatedJsonDiscoveryOnRepeatedRequests() throws Exception {
    Map<String, Object> expected =
        Map.of(
            "service", "paygate-agent-trust",
            "description", "Paid, signed agent trust reports over Lightning.",
            "status", "live",
            "links",
                Map.of(
                    "catalog", "https://paygate-agent-trust.fly.dev/api/v1/catalog",
                    "github", "https://github.com/greenharborlabs/paygate-agent-trust",
                    "documentation", "https://github.com/greenharborlabs/paygate-agent-trust#readme"));

    HttpResponse<String> first = get("/");
    HttpResponse<String> second = get("/");

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(MAPPER.readValue(first.body(), Map.class)).isEqualTo(expected);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(MAPPER.readValue(second.body(), Map.class)).isEqualTo(expected);
  }

  private Map<String, Object> getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readValue(response.body(), Map.class);
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
