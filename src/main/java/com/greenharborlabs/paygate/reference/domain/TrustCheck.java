package com.greenharborlabs.paygate.reference.domain;

public enum TrustCheck {
  DNS,
  TLS,
  HTTP,
  ROBOTS;

  public static TrustCheck parse(String value) {
    return switch (value.toLowerCase()) {
      case "dns" -> DNS;
      case "tls" -> TLS;
      case "http" -> HTTP;
      case "robots" -> ROBOTS;
      default -> null;
    };
  }
}
