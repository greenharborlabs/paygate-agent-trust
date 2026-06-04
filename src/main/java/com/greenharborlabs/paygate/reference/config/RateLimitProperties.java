package com.greenharborlabs.paygate.reference.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reference.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    @Min(1) @Max(10000) int catalogPerMinute,
    @Min(1) @Max(10000) int keysPerMinute,
    @Min(1) @Max(10000) int quotePerMinute,
    @Min(1) @Max(10000) int verifyPerMinute,
    @Min(1) @Max(10000) int reportPerMinute,
    @Min(1) @Max(1440) int bucketTtlMinutes) {

  public RateLimitProperties {
    if (catalogPerMinute == 0) catalogPerMinute = 120;
    if (keysPerMinute == 0) keysPerMinute = 120;
    if (quotePerMinute == 0) quotePerMinute = 60;
    if (verifyPerMinute == 0) verifyPerMinute = 30;
    if (reportPerMinute == 0) reportPerMinute = 10;
    if (bucketTtlMinutes == 0) bucketTtlMinutes = 30;
  }

  public Duration bucketTtl() {
    return Duration.ofMinutes(bucketTtlMinutes);
  }
}
