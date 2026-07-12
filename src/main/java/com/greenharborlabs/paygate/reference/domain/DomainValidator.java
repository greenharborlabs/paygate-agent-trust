package com.greenharborlabs.paygate.reference.domain;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DomainValidator {
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+$");

  public String normalize(String input) {
    if (input == null || input.isBlank()) {
      throw new ApiProblem("INVALID_DOMAIN", HttpStatus.BAD_REQUEST, false, "Domain is required.");
    }
    String trimmed = input.trim().toLowerCase(Locale.ROOT);
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("/")) {
      throw new ApiProblem("INVALID_DOMAIN", HttpStatus.BAD_REQUEST, false, "Only bare domains are supported.");
    }
    if (IPV4.matcher(trimmed).matches() || (trimmed.contains(":") && IPV6.matcher(trimmed).matches())) {
      throw new ApiProblem("INVALID_DOMAIN", HttpStatus.BAD_REQUEST, false, "Raw IP addresses are not supported.");
    }
    try {
      return IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (Exception ex) {
      throw new ApiProblem("INVALID_DOMAIN", HttpStatus.BAD_REQUEST, false, "Malformed domain.", ex);
    }
  }
}
