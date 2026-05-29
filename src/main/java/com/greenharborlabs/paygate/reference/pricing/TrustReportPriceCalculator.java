package com.greenharborlabs.paygate.reference.pricing;

import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import org.springframework.stereotype.Component;

@Component
public class TrustReportPriceCalculator {
  public long calculate(TrustReportRequest request, long basePrice) {
    long price = basePrice;
    if (request.checks().contains(TrustCheck.TLS)) price += 5;
    if (request.checks().contains(TrustCheck.HTTP)) price += 10;
    if (request.checks().contains(TrustCheck.REDIRECTS)) price += 5;
    if (request.checks().contains(TrustCheck.ROBOTS)) price += 5;
    if (request.checks().contains(TrustCheck.SECURITY_HEADERS)) price += 5;
    if (request.checks().contains(TrustCheck.CONTENT)) price += 5;
    if (request.checks().contains(TrustCheck.RISK)) price += 5;
    return Math.min(price, 50);
  }
}
