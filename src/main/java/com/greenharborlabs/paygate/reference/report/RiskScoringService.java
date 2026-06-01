package com.greenharborlabs.paygate.reference.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RiskScoringService {
  private static final List<String> EXPECTED_CHECKS =
      List.of("dns", "tls", "http", "redirects", "robots", "security_headers", "content");
  private static final List<String> DEFERRED_PROVIDER_CHECKS =
      List.of("providers.phishing_malware", "providers.reputation", "providers.domain_registration");
  private static final List<SecurityHeaderRule> SECURITY_HEADER_RULES =
      List.of(
          new SecurityHeaderRule("hsts", 15, 8),
          new SecurityHeaderRule("csp", 15, 8),
          new SecurityHeaderRule("frame_protection", 10, 6),
          new SecurityHeaderRule("referrer_policy", 5, 3),
          new SecurityHeaderRule("permissions_policy", 5, 3),
          new SecurityHeaderRule("x_content_type_options", 10, 6));

  Map<String, Object> score(Map<String, Object> checks) {
    List<Map<String, Object>> explanations = new ArrayList<>();
    List<Map<String, Object>> notEvaluated = new ArrayList<>();

    int score = 0;
    for (String checkName : EXPECTED_CHECKS) {
      Object check = checks.get(checkName);
      if (!(check instanceof Map<?, ?> checkMap)) {
        notEvaluated.add(notEvaluated("checks." + checkName, "not_selected"));
        continue;
      }
      Map<String, Object> typedCheck = stringKeyMap(checkMap);
      if ("not_evaluated".equals(typedCheck.get("status"))) {
        notEvaluated.add(notEvaluated("checks." + checkName, stringValue(typedCheck.get("reason"), "not_evaluated")));
        continue;
      }
      score += scoreCheck(checkName, typedCheck, explanations);
    }
    for (String providerCheck : DEFERRED_PROVIDER_CHECKS) {
      notEvaluated.add(notEvaluated(providerCheck, "deferred_provider_not_configured"));
    }

    int boundedScore = Math.min(100, Math.max(0, score));
    Map<String, Object> risk = new LinkedHashMap<>();
    risk.put("status", boundedScore == 0 && notEvaluated.isEmpty() ? "ok" : "warn");
    risk.put("score", boundedScore);
    risk.put("level", level(boundedScore));
    risk.put("explanations", explanations);
    risk.put("notEvaluated", notEvaluated);
    return risk;
  }

  private int scoreCheck(String checkName, Map<String, Object> check, List<Map<String, Object>> explanations) {
    return switch (checkName) {
      case "tls" -> scoreTls(check, explanations);
      case "redirects" -> scoreRedirects(check, explanations);
      case "robots" -> scoreRobots(check, explanations);
      case "security_headers" -> scoreSecurityHeaders(check, explanations);
      case "content" -> scoreContent(check, explanations);
      default -> scoreUncertainty(checkName, check, explanations);
    };
  }

  private int scoreTls(Map<String, Object> tls, List<Map<String, Object>> explanations) {
    int score = 0;
    Long daysUntilExpiry = longValue(tls.get("daysUntilExpiry"));
    if (daysUntilExpiry != null) {
      if (daysUntilExpiry < 0) {
        score += add(explanations, 70, "checks.tls.daysUntilExpiry", daysUntilExpiry, "TLS certificate is expired.");
      } else if (daysUntilExpiry <= 14) {
        score += add(explanations, 35, "checks.tls.daysUntilExpiry", daysUntilExpiry, "TLS certificate expires soon.");
      } else if (daysUntilExpiry <= 30) {
        score += add(explanations, 25, "checks.tls.daysUntilExpiry", daysUntilExpiry, "TLS certificate is nearing expiry.");
      }
    }
    if (Boolean.TRUE.equals(tls.get("hostnameMatched"))) {
      return score + scoreUncertainty("tls", tls, explanations);
    }
    if (Boolean.FALSE.equals(tls.get("hostnameMatched"))) {
      score += add(explanations, 50, "checks.tls.hostnameMatched", false, "TLS certificate hostname does not match.");
    }
    if (Boolean.TRUE.equals(tls.get("notYetValid"))) {
      score += add(explanations, 50, "checks.tls.notYetValid", true, "TLS certificate is not valid yet.");
    }
    return score + scoreUncertainty("tls", tls, explanations);
  }

  private int scoreRedirects(Map<String, Object> redirects, List<Map<String, Object>> explanations) {
    int score = scoreUncertainty("redirects", redirects, explanations);
    List<?> hops = listValue(redirects.get("hops"));
    for (int i = 0; i < hops.size(); i++) {
      if (!(hops.get(i) instanceof Map<?, ?> hop)) continue;
      Map<String, Object> typedHop = stringKeyMap(hop);
      String location = stringValue(typedHop.get("location"), null);
      if (looksLikeLogin(location)) {
        return score
            + add(
                explanations,
                35,
                "checks.redirects.hops[" + i + "].location",
                location,
                "Redirect chain points to a login route.");
      }
      String url = stringValue(typedHop.get("url"), null);
      if (looksLikeLogin(url)) {
        return score
            + add(
                explanations,
                35,
                "checks.redirects.hops[" + i + "].url",
                url,
                "Redirect chain lands on a login route.");
      }
    }
    for (String warning : stringList(redirects.get("warnings"))) {
      if ("redirect-unsafe-target".equals(warning)) {
        score += add(explanations, 45, "checks.redirects.warnings", warning, "Redirect target was blocked as unsafe.");
      } else if ("redirect-non-https".equals(warning)) {
        score += add(explanations, 35, "checks.redirects.warnings", warning, "Redirect chain downgrades from HTTPS.");
      }
    }
    return score;
  }

  private int scoreRobots(Map<String, Object> robots, List<Map<String, Object>> explanations) {
    int score = scoreUncertainty("robots", robots, explanations);
    Map<String, Object> agents = mapValue(robots.get("agents"));
    for (String agent : List.of("*", "GPTBot", "ChatGPT-User", "ClaudeBot", "Google-Extended", "PerplexityBot")) {
      Map<String, Object> policy = mapValue(agents.get(agent));
      if (Boolean.FALSE.equals(policy.get("allowed"))) {
        return score
            + add(
                explanations,
                25,
                "checks.robots.agents." + agent + ".allowed",
                false,
                "robots.txt blocks the root path for an evaluated agent.");
      }
    }
    return score;
  }

  private int scoreSecurityHeaders(Map<String, Object> securityHeaders, List<Map<String, Object>> explanations) {
    int score = scoreUncertainty("security_headers", securityHeaders, explanations);
    Map<String, Object> findings = mapValue(securityHeaders.get("findings"));
    for (SecurityHeaderRule rule : SECURITY_HEADER_RULES) {
      Map<String, Object> finding = mapValue(findings.get(rule.name()));
      String state = stringValue(finding.get("state"), null);
      if ("missing".equals(state)) {
        score +=
            add(
                explanations,
                rule.missingWeight(),
                "checks.security_headers.findings." + rule.name() + ".state",
                state,
                "Expected security header finding is missing.");
      } else if ("weak".equals(state)) {
        score +=
            add(
                explanations,
                rule.weakWeight(),
                "checks.security_headers.findings." + rule.name() + ".state",
                state,
                "Expected security header finding is weak.");
      }
    }
    return score;
  }

  private int scoreContent(Map<String, Object> content, List<Map<String, Object>> explanations) {
    int score = scoreUncertainty("content", content, explanations);
    if (Boolean.TRUE.equals(mapValue(content.get("login")).get("detected"))) {
      score += add(explanations, 35, "checks.content.login.detected", true, "Fetched content appears to be a login page.");
    }
    if (Boolean.TRUE.equals(mapValue(content.get("paywall")).get("detected"))) {
      score += add(explanations, 20, "checks.content.paywall.detected", true, "Fetched content appears paywalled.");
    }
    if (Boolean.TRUE.equals(mapValue(content.get("robots")).get("noindex"))) {
      score += add(explanations, 25, "checks.content.robots.noindex", true, "Fetched content is marked noindex.");
    }
    return score;
  }

  private int scoreUncertainty(String checkName, Map<String, Object> check, List<Map<String, Object>> explanations) {
    String status = stringValue(check.get("status"), "");
    if ("failed".equals(status)) {
      return add(explanations, 25, "checks." + checkName + ".status", status, "Check failed and leaves trust uncertainty.");
    }
    boolean unanalyzed = Boolean.FALSE.equals(check.get("analyzed"));
    boolean warnWithoutStructuredSignal =
        "warn".equals(status) && (stringList(check.get("warnings")).contains("fetch-failed") || check.containsKey("reason"));
    if (unanalyzed || warnWithoutStructuredSignal) {
      int points = "tls".equals(checkName) ? 25 : 20;
      return add(explanations, points, "checks." + checkName + ".status", status, "Check was only partially evaluated.");
    }
    return 0;
  }

  private int add(
      List<Map<String, Object>> explanations, int points, String path, Object evidence, String message) {
    Map<String, Object> explanation = new LinkedHashMap<>();
    explanation.put("path", path);
    explanation.put("evidence", evidence);
    explanation.put("points", points);
    explanation.put("message", message);
    explanations.add(explanation);
    return points;
  }

  private Map<String, Object> notEvaluated(String path, String reason) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("path", path);
    entry.put("reason", reason);
    return entry;
  }

  private String level(int score) {
    if (score >= 75) return "critical";
    if (score >= 50) return "high";
    if (score >= 25) return "medium";
    return "low";
  }

  private boolean looksLikeLogin(String value) {
    if (value == null) return false;
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.contains("/login") || lower.contains("/signin") || lower.contains("/sign-in");
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) return number.longValue();
    if (value instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private Map<String, Object> mapValue(Object value) {
    if (!(value instanceof Map<?, ?> map)) return Map.of();
    return stringKeyMap(map);
  }

  private List<?> listValue(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().map(String::valueOf).toList();
  }

  private String stringValue(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  private Map<String, Object> stringKeyMap(Map<?, ?> map) {
    Map<String, Object> typed = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      typed.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return typed;
  }

  private record SecurityHeaderRule(String name, int missingWeight, int weakWeight) {}
}
