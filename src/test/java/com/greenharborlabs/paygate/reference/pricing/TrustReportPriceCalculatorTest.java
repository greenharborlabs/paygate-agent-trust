package com.greenharborlabs.paygate.reference.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrustReportPriceCalculatorTest {
  private final TrustReportPriceCalculator calc = new TrustReportPriceCalculator();

  @Test
  void computesContributionsAndCap() {
    var req =
        new TrustReportRequest(
            "example.com",
            "example.com",
            Set.of(TrustCheck.DNS, TrustCheck.TLS, TrustCheck.HTTP, TrustCheck.ROBOTS));
    assertThat(calc.calculate(req, 10)).isEqualTo(30);
    assertThat(calc.calculate(req, 45)).isEqualTo(50);
  }

  @Test
  void pricesNewChecksAndCapsComprehensiveDefault() {
    var newChecks =
        Set.of(
            TrustCheck.DNS,
            TrustCheck.REDIRECTS,
            TrustCheck.SECURITY_HEADERS,
            TrustCheck.CONTENT,
            TrustCheck.RISK);
    assertThat(calc.calculate(new TrustReportRequest("example.com", "example.com", newChecks), 10))
        .isEqualTo(30);

    var comprehensive = new LinkedHashSet<>(TrustCheck.comprehensiveDefault());
    assertThat(calc.calculate(new TrustReportRequest("example.com", "example.com", comprehensive), 10))
        .isEqualTo(50);
  }
}
