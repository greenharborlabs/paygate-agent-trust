package com.greenharborlabs.paygate.reference.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SecurityHeadersAnalyzer {
  private static final long MIN_HSTS_MAX_AGE_SECONDS = 15_552_000L;

  Map<String, Object> analyze(Map<String, String> headers) {
    Map<String, String> normalized = normalize(headers);
    Map<String, Object> findings = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();

    findings.put("hsts", hsts(normalized.get("strict-transport-security"), warnings));
    Map<String, Object> csp = csp(normalized.get("content-security-policy"), warnings);
    findings.put("csp", csp);
    findings.put("x_frame_options", xFrameOptions(normalized.get("x-frame-options")));
    findings.put(
        "frame_protection",
        frameProtection(
            normalized.get("x-frame-options"),
            normalized.get("content-security-policy"),
            String.valueOf(csp.get("state")),
            warnings));
    findings.put("referrer_policy", referrerPolicy(normalized.get("referrer-policy"), warnings));
    findings.put("permissions_policy", permissionsPolicy(normalized.get("permissions-policy"), warnings));
    findings.put(
        "x_content_type_options",
        xContentTypeOptions(normalized.get("x-content-type-options"), warnings));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", warnings.isEmpty() ? "ok" : "warn");
    result.put("findings", findings);
    result.put("warnings", warnings.stream().distinct().toList());
    return result;
  }

  private Map<String, String> normalize(Map<String, String> headers) {
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
    }
    return normalized;
  }

  private Map<String, Object> hsts(String value, List<String> warnings) {
    if (isBlank(value)) {
      warnings.add("hsts-missing");
      return finding("missing", null, "Strict-Transport-Security is missing.");
    }
    Long maxAge = hstsMaxAge(value);
    if (maxAge == null || maxAge < MIN_HSTS_MAX_AGE_SECONDS) {
      warnings.add("hsts-weak");
      return finding("weak", value, "HSTS max-age is missing or short.");
    }
    return finding("present", value, null);
  }

  private Long hstsMaxAge(String value) {
    for (String directive : value.split(";")) {
      String[] parts = directive.trim().split("=", 2);
      if (parts.length == 2 && "max-age".equalsIgnoreCase(parts[0])) {
        try {
          return Long.parseLong(parts[1].trim());
        } catch (NumberFormatException ex) {
          return null;
        }
      }
    }
    return null;
  }

  private Map<String, Object> csp(String value, List<String> warnings) {
    if (isBlank(value)) {
      warnings.add("csp-missing");
      return finding("missing", null, "Content-Security-Policy is missing.");
    }
    String lower = value.toLowerCase(Locale.ROOT);
    boolean hasDefault = lower.contains("default-src");
    boolean unsafeScript = lower.contains("'unsafe-inline'") || lower.contains("script-src *");
    if (!hasDefault || unsafeScript) {
      warnings.add("csp-weak");
      return finding("weak", value, "CSP is missing default-src or allows unsafe script sources.");
    }
    return finding("present", value, null);
  }

  private Map<String, Object> xFrameOptions(String value) {
    if (isBlank(value)) {
      return finding("missing", null, "X-Frame-Options is missing.");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("DENY".equals(normalized) || "SAMEORIGIN".equals(normalized)) {
      return finding("present", value, null);
    }
    return finding("weak", value, "X-Frame-Options is not DENY or SAMEORIGIN.");
  }

  private Map<String, Object> frameProtection(
      String xFrameOptions, String csp, String cspState, List<String> warnings) {
    boolean frameAncestors = csp != null && csp.toLowerCase(Locale.ROOT).contains("frame-ancestors");
    String xfoState = String.valueOf(xFrameOptions(xFrameOptions).get("state"));
    if ("present".equals(xfoState) || (frameAncestors && "present".equals(cspState))) {
      return finding("present", frameAncestors ? "frame-ancestors" : xFrameOptions, null);
    }
    if ("weak".equals(xfoState) || frameAncestors || "weak".equals(cspState)) {
      warnings.add("frame-protection-weak");
      return finding("weak", frameAncestors ? "frame-ancestors" : xFrameOptions, "Frame protection is weak.");
    }
    warnings.add("frame-protection-missing");
    return finding("missing", null, "X-Frame-Options or CSP frame-ancestors is missing.");
  }

  private Map<String, Object> referrerPolicy(String value, List<String> warnings) {
    if (isBlank(value)) {
      warnings.add("referrer-policy-missing");
      return finding("missing", null, "Referrer-Policy is missing.");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (List.of("no-referrer", "same-origin", "strict-origin", "strict-origin-when-cross-origin")
        .contains(normalized)) {
      return finding("present", value, null);
    }
    warnings.add("referrer-policy-weak");
    return finding("weak", value, "Referrer-Policy leaks more cross-origin information than recommended.");
  }

  private Map<String, Object> permissionsPolicy(String value, List<String> warnings) {
    if (value == null) {
      warnings.add("permissions-policy-missing");
      return finding("missing", null, "Permissions-Policy is missing.");
    }
    if (value.isBlank()) {
      warnings.add("permissions-policy-weak");
      return finding("weak", value, "Permissions-Policy is empty.");
    }
    return finding("present", value, null);
  }

  private Map<String, Object> xContentTypeOptions(String value, List<String> warnings) {
    if (isBlank(value)) {
      warnings.add("x-content-type-options-missing");
      return finding("missing", null, "X-Content-Type-Options is missing.");
    }
    if ("nosniff".equalsIgnoreCase(value.trim())) {
      return finding("present", value, null);
    }
    warnings.add("x-content-type-options-weak");
    return finding("weak", value, "X-Content-Type-Options is not nosniff.");
  }

  private Map<String, Object> finding(String state, String value, String reason) {
    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("state", state);
    if (value != null) finding.put("value", value);
    if (reason != null) finding.put("reason", reason);
    return finding;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
