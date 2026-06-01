package com.greenharborlabs.paygate.reference.domain;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TrustReportRequestParser {
  private final DomainValidator domainValidator;

  public TrustReportRequestParser(DomainValidator domainValidator) {
    this.domainValidator = domainValidator;
  }

  public TrustReportRequest parse(String domain, String checksParam) {
    String normalized = domainValidator.normalize(domain);
    Set<TrustCheck> checks = new LinkedHashSet<>();
    if (checksParam == null || checksParam.isBlank()) {
      checks.addAll(TrustCheck.comprehensiveDefault());
      return new TrustReportRequest(domain, normalized, checks);
    }
    for (String token : checksParam.split(",")) {
      String normalizedToken = token.trim();
      if (normalizedToken.isEmpty()) {
        continue;
      }
      TrustCheck check = TrustCheck.parse(normalizedToken);
      if (check == null) {
        throw new ApiProblem("UNSUPPORTED_CHECK", HttpStatus.BAD_REQUEST, false, "Unsupported check: " + normalizedToken);
      }
      checks.add(check);
    }
    if (checks.isEmpty()) {
      checks.addAll(TrustCheck.comprehensiveDefault());
    }
    return new TrustReportRequest(domain, normalized, checks);
  }
}
