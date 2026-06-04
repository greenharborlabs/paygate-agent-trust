package com.greenharborlabs.paygate.reference.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

public final class OpenApiSchemas {
  private OpenApiSchemas() {}

  @Schema(name = "HealthResponse", description = "Lightweight health check response.")
  public record HealthResponse(
      @Schema(description = "Health status.", example = "ok") String status) {}

  @Schema(name = "CatalogResponse", description = "Service metadata, pricing, verification URLs, and signing key.")
  public record CatalogResponse(
      @Schema(description = "Service identifier.", example = "paygate-reference-service") String service,
      @Schema(
              description = "All supported trust checks.",
              example = "[\"dns\",\"tls\",\"http\",\"redirects\",\"robots\",\"security_headers\",\"content\",\"risk\"]")
          List<String> checks,
      @Schema(
              description = "Checks used when the checks query parameter is omitted.",
              example = "[\"dns\",\"tls\",\"http\",\"redirects\",\"robots\",\"security_headers\",\"content\",\"risk\"]")
          List<String> defaultChecks,
      @Schema(description = "Satoshi pricing table.", implementation = Pricing.class) Map<String, Object> pricing,
      @Schema(description = "Verification endpoint URLs.", implementation = VerificationLinks.class)
          Map<String, Object> verification,
      @Schema(description = "Current report signing metadata.", implementation = SigningKey.class) Map<String, Object> signature) {}

  @Schema(name = "Pricing", description = "Report price table in satoshis.")
  public record Pricing(
      @Schema(description = "Base price.", example = "10") int base,
      @Schema(description = "TLS check add-on price.", example = "5") int tls,
      @Schema(description = "HTTP check add-on price.", example = "10") int http,
      @Schema(description = "Redirect check add-on price.", example = "5") int redirects,
      @Schema(description = "Robots policy check add-on price.", example = "5") int robots,
      @Schema(description = "Security headers check add-on price.", example = "5") int securityHeaders,
      @Schema(description = "Content signal check add-on price.", example = "5") int content,
      @Schema(description = "Risk scoring check add-on price.", example = "5") int risk,
      @Schema(description = "Maximum report price.", example = "50") int cap) {}

  @Schema(name = "VerificationLinks", description = "Verification-related API paths.")
  public record VerificationLinks(
      @Schema(description = "Report signing key discovery URL.", example = "/api/v1/verification/keys") String keysUrl,
      @Schema(description = "Report verification URL.", example = "/api/v1/trust/verify") String verifyUrl) {}

  @Schema(name = "SigningKey", description = "Published report signing key metadata.")
  public record SigningKey(
      @Schema(description = "Signature algorithm.", example = "Ed25519") String algorithm,
      @Schema(description = "Signing key identifier.", example = "local-dev") String keyId,
      @Schema(description = "Base64 DER X.509 public key.", example = "MCowBQYDK2VwAyEA...") String publicKey) {}

  @Schema(name = "QuoteResponse", description = "Quoted report price for a normalized domain and check set.")
  public record QuoteResponse(
      @Schema(description = "Normalized bare domain.", example = "example.com") String domain,
      @Schema(description = "Price in satoshis.", example = "50") long priceSats) {}

  @Schema(name = "VerificationKeysResponse", description = "JWKS-like Ed25519 verification key response.")
  public record VerificationKeysResponse(
      @Schema(description = "Available report verification keys.") List<JwkKey> keys) {}

  @Schema(name = "JwkKey", description = "JWKS-like Ed25519 public verification key.")
  public record JwkKey(
      @Schema(description = "Key type.", example = "OKP") String kty,
      @Schema(description = "Curve.", example = "Ed25519") String crv,
      @Schema(description = "Key identifier.", example = "local-dev") String kid,
      @Schema(description = "Signing algorithm.", example = "EdDSA") String alg,
      @Schema(description = "Key use.", example = "sig") String use,
      @Schema(description = "Raw public key as base64url.", example = "geVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM")
          String x) {}

  @Schema(name = "TrustReportResponse", description = "Signed trust report response.")
  public record TrustReportResponse(
      @Schema(description = "Server-generated report identifier.", example = "tr_1760000000000") String reportId,
      @Schema(description = "SHA-256 digest of the canonical signed payload.", example = "sha256:abc123")
          String reportDigest,
      @Schema(description = "Report signature metadata.") ReportSignature signature,
      @Schema(description = "Normalized bare domain.", example = "example.com") String domain,
      @Schema(description = "ISO-8601 report generation timestamp.", example = "2026-06-03T12:00:00Z")
          String checkedAt,
      @Schema(
              description = "Selected trust check results keyed by check name.",
              example =
                  """
                  {
                    "dns": {"answers": ["93.184.216.34"]},
                    "risk": {"status": "ok", "score": 0, "level": "low", "explanations": [], "notEvaluated": []}
                  }
                  """)
          Map<String, Object> checks,
      @Schema(description = "Risk result when the risk check is selected.", implementation = Map.class) Map<String, Object> risk,
      @Schema(description = "Overall report verdict.") Verdict verdict,
      @Schema(description = "Report cache metadata.") CacheInfo cache,
      @Schema(description = "Optional binding between a Paygate payment receipt and this signed report.")
          ReceiptBinding receiptBinding) {}

  @Schema(name = "ReportSignature", description = "Report signature metadata and value.")
  public record ReportSignature(
      @Schema(description = "Signature algorithm.", example = "Ed25519") String algorithm,
      @Schema(description = "Signing key identifier.", example = "local-dev") String keyId,
      @Schema(description = "Base64url signature value.", example = "base64url-signature") String value) {}

  @Schema(name = "Verdict", description = "Overall report status and warnings.")
  public record Verdict(
      @Schema(description = "Verdict status.", allowableValues = {"ok", "warn"}, example = "warn") String status,
      @Schema(description = "Warnings collected while generating the report.", example = "[\"security_headers:hsts-missing\"]")
          List<String> warnings) {}

  @Schema(name = "CacheInfo", description = "Report cache status.")
  public record CacheInfo(
      @Schema(description = "Whether this response came from the report cache.", example = "false") boolean hit,
      @Schema(description = "Report cache TTL in seconds.", example = "900") long ttlSeconds) {}

  @Schema(name = "ReceiptBinding", description = "Signed binding between payment receipt and trust report.")
  public record ReceiptBinding(
      @Schema(description = "Payment-Receipt response header value.", example = "payment-receipt-header-value")
          String receipt,
      @Schema(description = "Report digest covered by the binding.", example = "sha256:abc123") String reportDigest,
      @Schema(description = "Report signature covered by the binding.", example = "base64url-signature")
          String reportSignature,
      @Schema(description = "Digest of the receipt binding payload.", example = "sha256:def456") String bindingDigest,
      @Schema(description = "Binding signature metadata.", implementation = ReportSignature.class) Map<String, Object> signature) {}

  @Schema(name = "VerifyReportResponse", description = "Verification result for a signed trust report.")
  public record VerifyReportResponse(
      @Schema(description = "Whether every verification check passed.", example = "true") boolean valid,
      @Schema(description = "Report digest supplied by the report.", example = "sha256:abc123") String reportDigest,
      @Schema(description = "Signing key identifier used by the report.", example = "local-dev") String keyId,
      @Schema(description = "Whether the Ed25519 signature is valid.", example = "true") boolean signatureValid,
      @Schema(description = "Whether the report digest matches the canonical payload.", example = "true")
          boolean digestMatches,
      @Schema(description = "Whether the optional receipt binding is valid.", example = "true") Boolean receiptBindingValid) {}

  @Schema(name = "ApiProblemResponse", description = "Problem details response with Paygate Agent Trust fields.")
  public record ApiProblemResponse(
      @Schema(description = "Problem type URI.", example = "https://paygate-reference.greenharborlabs.com/problems/invalid_domain")
          String type,
      @Schema(description = "Problem title.", example = "INVALID DOMAIN") String title,
      @Schema(description = "HTTP status code.", example = "400") int status,
      @Schema(description = "Human-readable error detail.", example = "Domain must be a bare domain name.") String detail,
      @Schema(description = "Machine-readable error code.", example = "INVALID_DOMAIN") String code,
      @Schema(description = "Whether retrying may succeed.", example = "false") boolean retryable) {}
}
