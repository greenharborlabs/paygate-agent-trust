package com.greenharborlabs.paygate.reference.pricing;

import com.greenharborlabs.paygate.reference.domain.TrustReportRequestParser;
import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component("trustReportPricer")
public class TrustReportPricer implements PaygatePricingStrategy {
  private final TrustReportRequestParser parser;
  private final TrustReportPriceCalculator calculator;

  public TrustReportPricer(TrustReportRequestParser parser, TrustReportPriceCalculator calculator) {
    this.parser = parser;
    this.calculator = calculator;
  }

  @Override
  public long calculatePrice(HttpServletRequest request, long defaultPrice) {
    var parsed = parser.parse(request.getParameter("domain"), request.getParameter("checks"));
    return calculator.calculate(parsed, defaultPrice);
  }
}
