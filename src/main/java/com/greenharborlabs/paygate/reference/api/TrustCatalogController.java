package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Catalog", description = "Service health, capability discovery, pricing, and verification metadata.")
public class TrustCatalogController {
  private final PaygateReferenceProperties properties;

  public TrustCatalogController(PaygateReferenceProperties properties) {
    this.properties = properties;
  }

  @Operation(
      summary = "Health check",
      description = "Returns a lightweight status response for load balancers and uptime checks.",
      responses =
          @ApiResponse(
              responseCode = "200",
              description = "Service is healthy.",
              content = @Content(schema = @Schema(implementation = OpenApiSchemas.HealthResponse.class))))
  @GetMapping("/healthz")
  public Map<String, String> healthz() {
    return Map.of("status", "ok");
  }

  @Operation(
      summary = "Get service catalog",
      description = "Returns supported trust checks, default check set, pricing, verification URLs, and report signing key metadata.",
      responses =
          {
            @ApiResponse(
                responseCode = "200",
                description = "Catalog metadata.",
                content = @Content(schema = @Schema(implementation = OpenApiSchemas.CatalogResponse.class))),
            @ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded.",
                headers = {
                  @Header(name = "Retry-After", description = "Seconds to wait before retrying."),
                  @Header(name = "RateLimit-Limit", description = "Request limit for this route and client."),
                  @Header(name = "RateLimit-Remaining", description = "Remaining requests in the current bucket."),
                  @Header(name = "RateLimit-Reset", description = "Seconds until another request is available.")
                },
                content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class)))
          })
  @GetMapping("/api/v1/catalog")
  public Map<String, Object> catalog() {
    return Map.of(
        "service", "paygate-reference-service",
        "checks", List.of("dns", "tls", "http", "redirects", "robots", "security_headers", "content", "risk"),
        "defaultChecks", List.of("dns", "tls", "http", "redirects", "robots", "security_headers", "content", "risk"),
        "pricing",
            Map.of(
                "base", 10,
                "tls", 5,
                "http", 10,
                "redirects", 5,
                "robots", 5,
                "security_headers", 5,
                "content", 5,
                "risk", 5,
                "cap", 50),
        "verification",
            Map.of(
                "keysUrl", "/api/v1/verification/keys",
                "verifyUrl", "/api/v1/trust/verify"),
        "signature", Map.of("algorithm", "Ed25519", "keyId", properties.reportSigningKeyId(), "publicKey", properties.reportSigningPublicKey()));
  }
}
