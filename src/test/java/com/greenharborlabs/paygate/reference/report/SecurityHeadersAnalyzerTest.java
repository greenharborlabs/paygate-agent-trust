package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityHeadersAnalyzerTest {
  private final SecurityHeadersAnalyzer analyzer = new SecurityHeadersAnalyzer();

  @Test
  @SuppressWarnings("unchecked")
  void reportsStrongAndMissingSecurityHeadersDeterministically() {
    Map<String, Object> result =
        analyzer.analyze(
            Map.of(
                "Strict-Transport-Security", "max-age=31536000; includeSubDomains",
                "Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'",
                "Referrer-Policy", "strict-origin-when-cross-origin",
                "Permissions-Policy", "geolocation=()",
                "X-Content-Type-Options", "nosniff"));

    Map<String, Object> findings = (Map<String, Object>) result.get("findings");

    assertThat(result).containsEntry("status", "ok");
    assertThat((Map<String, Object>) findings.get("hsts")).containsEntry("state", "present");
    assertThat((Map<String, Object>) findings.get("csp")).containsEntry("state", "present");
    assertThat((Map<String, Object>) findings.get("frame_protection")).containsEntry("state", "present");
    assertThat((Map<String, Object>) findings.get("x_frame_options")).containsEntry("state", "missing");
    assertThat((List<String>) result.get("warnings")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportsWeakHeaderStates() {
    Map<String, Object> result =
        analyzer.analyze(
            Map.of(
                "Strict-Transport-Security", "max-age=120",
                "Content-Security-Policy", "script-src * 'unsafe-inline'",
                "X-Frame-Options", "ALLOW-FROM https://example.com",
                "Referrer-Policy", "unsafe-url",
                "Permissions-Policy", "",
                "X-Content-Type-Options", "maybe"));

    Map<String, Object> findings = (Map<String, Object>) result.get("findings");

    assertThat(result).containsEntry("status", "warn");
    assertThat((Map<String, Object>) findings.get("hsts")).containsEntry("state", "weak");
    assertThat((Map<String, Object>) findings.get("csp")).containsEntry("state", "weak");
    assertThat((Map<String, Object>) findings.get("frame_protection")).containsEntry("state", "weak");
    assertThat((Map<String, Object>) findings.get("referrer_policy")).containsEntry("state", "weak");
    assertThat((Map<String, Object>) findings.get("permissions_policy")).containsEntry("state", "weak");
    assertThat((Map<String, Object>) findings.get("x_content_type_options")).containsEntry("state", "weak");
    assertThat((List<String>) result.get("warnings"))
        .contains(
            "hsts-weak",
            "csp-weak",
            "frame-protection-weak",
            "referrer-policy-weak",
            "permissions-policy-weak",
            "x-content-type-options-weak");
  }
}
