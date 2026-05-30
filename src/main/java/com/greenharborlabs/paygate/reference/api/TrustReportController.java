package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.domain.TrustReportRequestParser;
import com.greenharborlabs.paygate.reference.pricing.TrustReportPriceCalculator;
import com.greenharborlabs.paygate.reference.report.TrustReportService;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustReportController {
  private final TrustReportRequestParser parser;
  private final TrustReportPriceCalculator calculator;
  private final TrustReportService trustReportService;

  public TrustReportController(
      TrustReportRequestParser parser,
      TrustReportPriceCalculator calculator,
      TrustReportService trustReportService) {
    this.parser = parser;
    this.calculator = calculator;
    this.trustReportService = trustReportService;
  }

  @GetMapping("/api/v1/trust/quote")
  public Map<String, Object> quote(@RequestParam String domain, @RequestParam(required = false) String checks) {
    var req = parser.parse(domain, checks);
    return Map.of("domain", req.normalizedDomain(), "priceSats", calculator.calculate(req, 10));
  }

  @PaymentRequired(priceSats = 10, pricingStrategy = "trustReportPricer")
  @GetMapping("/api/v1/trust/report")
  public Map<String, Object> report(
      @RequestParam String domain, @RequestParam(required = false) String checks, HttpServletResponse response) {
    Map<String, Object> report = trustReportService.createReport(parser.parse(domain, checks));
    return trustReportService.bindReceipt(report, response.getHeader("Payment-Receipt"));
  }
}
