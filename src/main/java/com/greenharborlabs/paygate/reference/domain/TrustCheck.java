package com.greenharborlabs.paygate.reference.domain;

import java.util.List;

public enum TrustCheck {
  DNS,
  TLS,
  HTTP,
  REDIRECTS,
  ROBOTS,
  SECURITY_HEADERS,
  CONTENT,
  RISK;

  public static TrustCheck parse(String value) {
    return switch (value.toLowerCase()) {
      case "dns" -> DNS;
      case "tls" -> TLS;
      case "http" -> HTTP;
      case "redirects" -> REDIRECTS;
      case "robots" -> ROBOTS;
      case "security_headers" -> SECURITY_HEADERS;
      case "content" -> CONTENT;
      case "risk" -> RISK;
      default -> null;
    };
  }

  public static List<TrustCheck> comprehensiveDefault() {
    return List.of(DNS, TLS, HTTP, REDIRECTS, ROBOTS, SECURITY_HEADERS, CONTENT, RISK);
  }
}
