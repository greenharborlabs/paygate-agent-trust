package com.greenharborlabs.paygate.reference.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class ContentAnalyzer {
  private static final int SNIPPET_LIMIT = 512;
  private static final Pattern PASSWORD_INPUT =
      Pattern.compile("<input\\b[^>]*type\\s*=\\s*['\"]?password", Pattern.CASE_INSENSITIVE);
  private static final Pattern LOGIN_FORM =
      Pattern.compile("<form\\b[^>]*(login|signin|sign-in)|/(login|signin|sign-in)", Pattern.CASE_INSENSITIVE);
  private static final Pattern PAYWALL =
      Pattern.compile(
          "\\b(paywall|subscribe to continue|subscriber-only|subscription required|members-only|become a subscriber)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern META_NOINDEX =
      Pattern.compile(
          "<meta\\b[^>]*(name|property)\\s*=\\s*['\"]?robots['\"]?[^>]*content\\s*=\\s*['\"]?[^>]*noindex",
          Pattern.CASE_INSENSITIVE);

  Map<String, Object> analyze(Map<String, String> headers, String body) {
    String contentType = header(headers, "content-type");
    String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("contentType", contentType == null ? "unknown" : contentType);

    if (isUnsupportedBinary(normalizedContentType, body)) {
      result.put("status", "warn");
      result.put("analyzed", false);
      result.put("reason", "unsupported_binary_content");
      result.put("warnings", List.of("unsupported-content"));
      return result;
    }

    String safeBody = body == null ? "" : body;
    String kind = kind(normalizedContentType, safeBody);
    if ("unknown".equals(kind)) {
      result.put("status", "warn");
      result.put("analyzed", false);
      result.put("reason", "unsupported_content");
      result.put("warnings", List.of("unsupported-content"));
      return result;
    }

    result.put("analyzed", true);
    result.put("kind", kind);
    result.put("snippet", safeBody.substring(0, Math.min(safeBody.length(), SNIPPET_LIMIT)));

    List<String> warnings = new ArrayList<>();
    boolean loginDetected = "html".equals(kind) && detectsLogin(safeBody);
    boolean paywallDetected = detectsPaywall(safeBody);
    boolean noindex = detectsNoindex(headers, safeBody);

    result.put("login", Map.of("detected", loginDetected));
    result.put("paywall", Map.of("detected", paywallDetected));
    result.put("robots", Map.of("noindex", noindex));

    if (loginDetected) warnings.add("login-form-detected");
    if (paywallDetected) warnings.add("paywall-detected");
    if (noindex) warnings.add("noindex-detected");
    result.put("warnings", warnings);
    result.put("status", warnings.isEmpty() ? "ok" : "warn");
    return result;
  }

  private String header(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
    }
    return null;
  }

  private boolean isUnsupportedBinary(String contentType, String body) {
    if (contentType.startsWith("image/")
        || contentType.startsWith("audio/")
        || contentType.startsWith("video/")
        || contentType.contains("application/octet-stream")
        || contentType.contains("application/pdf")
        || contentType.contains("application/zip")) {
      return true;
    }
    if (body == null || body.isEmpty()) return false;
    int checked = Math.min(body.length(), 256);
    for (int i = 0; i < checked; i++) {
      char c = body.charAt(i);
      if (c == 0 || (Character.isISOControl(c) && !Character.isWhitespace(c))) {
        return true;
      }
    }
    return false;
  }

  private String kind(String contentType, String body) {
    String trimmed = body.stripLeading();
    if (contentType.contains("text/html") || trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html")) {
      return "html";
    }
    if (contentType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return "api_json";
    }
    if (contentType.startsWith("text/")) {
      return "text";
    }
    if (contentType.contains("xml")) {
      return "document";
    }
    return "unknown";
  }

  private boolean detectsLogin(String body) {
    return PASSWORD_INPUT.matcher(body).find() || LOGIN_FORM.matcher(body).find();
  }

  private boolean detectsPaywall(String body) {
    return PAYWALL.matcher(body).find();
  }

  private boolean detectsNoindex(Map<String, String> headers, String body) {
    String xRobotsTag = header(headers, "x-robots-tag");
    return (xRobotsTag != null && xRobotsTag.toLowerCase(Locale.ROOT).contains("noindex"))
        || META_NOINDEX.matcher(body).find();
  }
}
