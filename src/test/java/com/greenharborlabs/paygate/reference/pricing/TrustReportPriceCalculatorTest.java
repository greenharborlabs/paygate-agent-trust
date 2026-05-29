package com.greenharborlabs.paygate.reference.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrustReportPriceCalculatorTest {
  private final TrustReportPriceCalculator calc = new TrustReportPriceCalculator();

  @Test
  void computesContributionsAndCap() {
    var req = new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.DNS, TrustCheck.TLS, TrustCheck.HTTP, TrustCheck.ROBOTS));
    assertThat(calc.calculate(req, 10)).isEqualTo(30);
    assertThat(calc.calculate(req, 45)).isEqualTo(50);
  }
}
