package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.domain.TrustReportRequestParser;
import com.greenharborlabs.paygate.reference.pricing.TrustReportPriceCalculator;
import com.greenharborlabs.paygate.reference.report.TrustReportService;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Trust Reports", description = "Quote and generate signed trust reports.")
public class TrustReportController {
  private final TrustReportRequestParser parser;
  private final TrustReportPriceCalculator calculator;
  private final TrustReportService trustReportService;

  public TrustReportController(
      TrustReportRequestParser parser,
      TrustReportPriceCalculator calculator,
      TrustReportService trustReportService) {
    this.parser = parser;
    this.calculator = calculator;
    this.trustReportService = trustReportService;
  }

  @Operation(
      summary = "Quote a trust report",
      description =
          "Returns the price in satoshis for a normalized bare domain and optional comma-separated check set.",
      parameters = {
        @Parameter(
            name = "domain",
            in = ParameterIn.QUERY,
            required = true,
            description = "Bare domain name. Do not include a URL scheme, path, or raw IP address.",
            example = "example.com"),
        @Parameter(
            name = "checks",
            in = ParameterIn.QUERY,
            description =
                "Optional comma-separated checks. When omitted or blank, the comprehensive default set is used.",
            example = "dns,tls,http,redirects,robots,security_headers,content,risk")
      },
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Report quote.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.QuoteResponse.class))),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid domain or unsupported check.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class))),
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
  @GetMapping("/api/v1/trust/quote")
  public Map<String, Object> quote(@RequestParam String domain, @RequestParam(required = false) String checks) {
    var req = parser.parse(domain, checks);
    return Map.of("domain", req.normalizedDomain(), "priceSats", calculator.calculate(req, 10));
  }

  @PaymentRequired(priceSats = 10, pricingStrategy = "trustReportPricer")
  @Operation(
      summary = "Generate a signed trust report",
      description =
          "Creates a paid, signed trust report. When Paygate is enabled, the first unauthenticated request returns a payment challenge and the paid retry uses `Authorization: Payment <credential>`.",
      security = @SecurityRequirement(name = "paymentAuth"),
      parameters = {
        @Parameter(
            name = "domain",
            in = ParameterIn.QUERY,
            required = true,
            description = "Bare domain name. Do not include a URL scheme, path, or raw IP address.",
            example = "example.com"),
        @Parameter(
            name = "checks",
            in = ParameterIn.QUERY,
            description =
                "Optional comma-separated checks. When omitted or blank, the comprehensive default set is used.",
            example = "dns,tls,http,redirects,robots,security_headers,content,risk")
      },
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Signed trust report.",
            headers = @Header(name = "Payment-Receipt", description = "Optional Paygate receipt header for paid MPP responses."),
            content =
                @Content(
                    schema = @Schema(implementation = OpenApiSchemas.TrustReportResponse.class),
                    examples =
                        @ExampleObject(
                            name = "dnsReport",
                            value =
                                """
                                {
                                  "reportId": "tr_1760000000000",
                                  "reportDigest": "sha256:abc123",
                                  "signature": {"algorithm": "Ed25519", "keyId": "local-dev", "value": "base64url-signature"},
                                  "domain": "example.com",
                                  "checkedAt": "2026-06-03T12:00:00Z",
                                  "checks": {"dns": {"answers": ["93.184.216.34"]}},
                                  "verdict": {"status": "ok", "warnings": []},
                                  "cache": {"hit": false, "ttlSeconds": 900}
                                }
                                """))),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid domain or unsupported check.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class))),
        @ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded.",
            headers = {
              @Header(name = "Retry-After", description = "Seconds to wait before retrying."),
              @Header(name = "RateLimit-Limit", description = "Request limit for this route and client."),
              @Header(name = "RateLimit-Remaining", description = "Remaining requests in the current bucket."),
              @Header(name = "RateLimit-Reset", description = "Seconds until another request is available.")
            },
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class))),
        @ApiResponse(
            responseCode = "402",
            description = "Payment required. Pay the challenge and retry with `Authorization: Payment <credential>`.",
            headers = @Header(name = "WWW-Authenticate", description = "Paygate challenge headers such as L402 and Payment."),
            content =
                @Content(
                    mediaType = "application/json",
                    examples =
                        @ExampleObject(
                            name = "paymentChallenge",
                            value =
                                """
                                {
                                  "protocols": {
                                    "Payment": {
                                      "service": "paygate-reference-service",
                                      "priceSats": 10
                                    }
                                  }
                                }
                                """))),
        @ApiResponse(
            responseCode = "503",
            description = "Report generation failed due to a retryable dependency or signing problem.",
            content = @Content(schema = @Schema(implementation = OpenApiSchemas.ApiProblemResponse.class)))
      })
  @GetMapping("/api/v1/trust/report")
  public Map<String, Object> report(
      @RequestParam String domain,
      @RequestParam(required = false) String checks,
      @Parameter(hidden = true) HttpServletResponse response) {
    Map<String, Object> report = trustReportService.createReport(parser.parse(domain, checks));
    return trustReportService.bindReceipt(report, response.getHeader("Payment-Receipt"));
  }
}
