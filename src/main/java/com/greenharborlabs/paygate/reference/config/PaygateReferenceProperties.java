package com.greenharborlabs.paygate.reference.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reference")
public record PaygateReferenceProperties(
    @Min(1) @Max(10) int dnsTimeoutSeconds,
    @Min(1) @Max(10) int connectTimeoutSeconds,
    @Min(1) @Max(10) int readTimeoutSeconds,
    @Min(1) @Max(20) int totalBudgetSeconds,
    @Min(1024) @Max(131072) int maxBodyBytes,
    @Min(1024) @Max(32768) int maxHeadersBytes,
    @Min(1) @Max(64) int maxHeadersCount,
    @Min(1) @Max(120) int cacheTtlMinutes,
    @Min(1) @Max(64) int maxConcurrentChecks,
    @NotBlank String reportSigningPrivateKey,
    @NotBlank String reportSigningPublicKey,
    @NotBlank String reportSigningKeyId) {

  public PaygateReferenceProperties {
    if (dnsTimeoutSeconds == 0) dnsTimeoutSeconds = 1;
    if (connectTimeoutSeconds == 0) connectTimeoutSeconds = 2;
    if (readTimeoutSeconds == 0) readTimeoutSeconds = 3;
    if (totalBudgetSeconds == 0) totalBudgetSeconds = 6;
    if (maxBodyBytes == 0) maxBodyBytes = 65536;
    if (maxHeadersBytes == 0) maxHeadersBytes = 8192;
    if (maxHeadersCount == 0) maxHeadersCount = 32;
    if (cacheTtlMinutes == 0) cacheTtlMinutes = 15;
    if (maxConcurrentChecks == 0) maxConcurrentChecks = 16;
  }

  public Duration cacheTtl() {
    return Duration.ofMinutes(cacheTtlMinutes);
  }
}
