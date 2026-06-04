package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.report.ReportVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Verification", description = "Report verification key discovery and signed report verification.")
public class VerificationController {
  private final ReportVerificationService verificationService;

  public VerificationController(ReportVerificationService verificationService) {
    this.verificationService = verificationService;
  }

  @Operation(
      summary = "Get verification keys",
      description = "Returns the active Ed25519 public key in a JWKS-like shape for independent report verification.",
      responses =
          @ApiResponse(
              responseCode = "200",
              description = "Verification keys.",
              content = @Content(schema = @Schema(implementation = OpenApiSchemas.VerificationKeysResponse.class))))
  @GetMapping("/api/v1/verification/keys")
  public Map<String, Object> keys() {
    return verificationService.keys();
  }

  @Operation(
      summary = "Verify a signed trust report",
      description = "Verifies the report digest, Ed25519 signature, and optional Paygate receipt binding.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              description = "A report previously returned by `/api/v1/trust/report`.",
              content =
                  @Content(
                      schema = @Schema(implementation = OpenApiSchemas.TrustReportResponse.class),
                      examples =
                          @ExampleObject(
                              name = "signedReport",
                              value =
                                  """
                                  {
                                    "reportDigest": "sha256:abc123",
                                    "signature": {"algorithm": "Ed25519", "keyId": "local-dev", "value": "base64url-signature"},
                                    "domain": "example.com",
                                    "checkedAt": "2026-06-03T12:00:00Z",
                                    "checks": {"dns": {"answers": ["93.184.216.34"]}},
                                    "verdict": {"status": "ok", "warnings": []}
                                  }
                                  """))),
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Verification result.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.VerifyReportResponse.class))),
        @ApiResponse(
            responseCode = "400",
            description = "Malformed report or signature.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class))),
        @ApiResponse(
            responseCode = "503",
            description = "Verification service failure.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class)))
      })
  @PostMapping("/api/v1/trust/verify")
  public Map<String, Object> verify(@RequestBody Map<String, Object> report) {
    return verificationService.verify(report);
  }
}
