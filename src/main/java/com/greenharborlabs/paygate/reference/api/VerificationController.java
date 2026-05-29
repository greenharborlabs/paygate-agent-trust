package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.report.ReportVerificationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerificationController {
  private final ReportVerificationService verificationService;

  public VerificationController(ReportVerificationService verificationService) {
    this.verificationService = verificationService;
  }

  @GetMapping("/api/v1/verification/keys")
  public Map<String, Object> keys() {
    return verificationService.keys();
  }

  @PostMapping("/api/v1/trust/verify")
  public Map<String, Object> verify(@RequestBody Map<String, Object> report) {
    return verificationService.verify(report);
  }
}
