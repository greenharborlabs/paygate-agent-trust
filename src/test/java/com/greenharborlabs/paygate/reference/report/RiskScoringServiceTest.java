package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskScoringServiceTest {
  private final RiskScoringService scorer = new RiskScoringService();

  @Test
  @SuppressWarnings("unchecked")
  void scoresRiskCasesDeterministicallyWithConcreteEvidencePaths() {
    List<Case> cases =
        List.of(
            new Case("low-risk", lowRiskChecks(), 0, "low", List.of()),
            new Case("expiring-cert", checks("tls", Map.of("daysUntilExpiry", 12L, "warnings", List.of("certificate-near-expiry"))), 35, "medium", List.of("checks.tls.daysUntilExpiry")),
            new Case("redirect-to-login", checks("redirects", redirects("/login")), 35, "medium", List.of("checks.redirects.hops[0].location")),
            new Case("missing-security-headers", checks("security_headers", missingSecurityHeaders()), 60, "high", List.of("checks.security_headers.findings.hsts.state", "checks.security_headers.findings.csp.state")),
            new Case("blocked-robots", checks("robots", blockedRobots()), 25, "medium", List.of("checks.robots.agents.*.allowed")),
            new Case("mixed-partial-failures", mixedPartialFailures(), 45, "medium", List.of("checks.tls.status", "checks.content.status")));

    for (Case testCase : cases) {
      Map<String, Object> first = scorer.score(testCase.checks());
      Map<String, Object> second = scorer.score(testCase.checks());
      List<Map<String, Object>> explanations = (List<Map<String, Object>>) first.get("explanations");

      assertThat(first).as(testCase.name()).isEqualTo(second);
      assertThat(first).containsEntry("score", testCase.score()).containsEntry("level", testCase.level());
      assertThat(explanations.stream().map(explanation -> String.valueOf(explanation.get("path"))).toList())
          .as(testCase.name())
          .containsAll(testCase.paths());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void deferredAndAbsentChecksAreNotEvaluatedInsteadOfLoweringRiskSilently() {
    Map<String, Object> checks = checks("tls", Map.of("status", "not_evaluated", "reason", "deferred_to_later_wave"));

    Map<String, Object> risk = scorer.score(checks);
    List<Map<String, Object>> notEvaluated = (List<Map<String, Object>>) risk.get("notEvaluated");

    assertThat(notEvaluated)
        .extracting(entry -> entry.get("path"))
        .contains(
            "checks.dns",
            "checks.tls",
            "checks.http",
            "checks.redirects",
            "checks.robots",
            "checks.security_headers",
            "checks.content",
            "providers.phishing_malware",
            "providers.reputation",
            "providers.domain_registration");
    assertThat(notEvaluated)
        .anySatisfy(
            entry ->
                assertThat(entry)
                    .containsEntry("path", "checks.tls")
                    .containsEntry("reason", "deferred_to_later_wave"));
    assertThat(notEvaluated)
        .filteredOn(entry -> String.valueOf(entry.get("path")).startsWith("providers."))
        .allSatisfy(entry -> assertThat(entry).containsEntry("reason", "deferred_provider_not_configured"));
  }

  private Map<String, Object> lowRiskChecks() {
    Map<String, Object> checks = new LinkedHashMap<>();
    checks.put("tls", Map.of("daysUntilExpiry", 180L, "warnings", List.of()));
    checks.put("redirects", Map.of("status", "ok", "hops", List.of(Map.of("url", "https://example.com/", "status", 200)), "warnings", List.of()));
    checks.put("robots", Map.of("status", "ok", "agents", Map.of("*", Map.of("allowed", true)), "warnings", List.of()));
    checks.put("security_headers", presentSecurityHeaders());
    checks.put("content", Map.of("status", "ok", "analyzed", true, "login", Map.of("detected", false), "paywall", Map.of("detected", false), "robots", Map.of("noindex", false), "warnings", List.of()));
    checks.put("dns", Map.of("answers", List.of("93.184.216.34")));
    checks.put("http", Map.of("statusCode", 200));
    return checks;
  }

  private Map<String, Object> mixedPartialFailures() {
    Map<String, Object> checks = new LinkedHashMap<>();
    checks.put("tls", Map.of("status", "warn", "reason", "TLS_HANDSHAKE_FAILED", "warnings", List.of("certificate-inspection-failed")));
    checks.put("content", Map.of("status", "warn", "analyzed", false, "reason", "FETCH_FAILED", "warnings", List.of("fetch-failed")));
    return checks;
  }

  private Map<String, Object> presentSecurityHeaders() {
    return Map.of(
        "status",
        "ok",
        "findings",
        Map.of(
            "hsts", Map.of("state", "present"),
            "csp", Map.of("state", "present"),
            "frame_protection", Map.of("state", "present"),
            "referrer_policy", Map.of("state", "present"),
            "permissions_policy", Map.of("state", "present"),
            "x_content_type_options", Map.of("state", "present")),
        "warnings",
        List.of());
  }

  private Map<String, Object> missingSecurityHeaders() {
    return Map.of(
        "status",
        "warn",
        "findings",
        Map.of(
            "hsts", Map.of("state", "missing"),
            "csp", Map.of("state", "missing"),
            "frame_protection", Map.of("state", "missing"),
            "referrer_policy", Map.of("state", "missing"),
            "permissions_policy", Map.of("state", "missing"),
            "x_content_type_options", Map.of("state", "missing")),
        "warnings",
        List.of("hsts-missing", "csp-missing"));
  }

  private Map<String, Object> redirects(String location) {
    return Map.of(
        "status",
        "ok",
        "hops",
        List.of(Map.of("url", "https://example.com/", "status", 302, "location", location)),
        "warnings",
        List.of());
  }

  private Map<String, Object> blockedRobots() {
    return Map.of(
        "status",
        "ok",
        "agents",
        Map.of("*", Map.of("allowed", false, "matchedRule", "disallow:/")),
        "warnings",
        List.of());
  }

  private Map<String, Object> checks(String key, Map<String, Object> value) {
    Map<String, Object> checks = new LinkedHashMap<>();
    checks.put(key, value);
    return checks;
  }

  private record Case(String name, Map<String, Object> checks, int score, String level, List<String> paths) {}
}
