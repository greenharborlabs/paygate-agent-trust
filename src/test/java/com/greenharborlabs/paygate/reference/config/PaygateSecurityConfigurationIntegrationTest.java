package com.greenharborlabs.paygate.reference.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.spring.PaygateProperties;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

class PaygateSecurityConfigurationIntegrationTest {
  @Test
  void bindsSecurityBoundsFromEnvironmentBackedSettings() throws IOException {
    PaygateProperties properties =
        bind(
            Map.of(
                "PAYGATE_REQUEST_BODY_MAX_BYTES", "16384",
                "PAYGATE_RATE_LIMIT_IPV6_PREFIX_LENGTH", "80"));

    assertThat(properties.getRequestBody().getMaxBytes()).isEqualTo(16384);
    assertThat(properties.getRateLimit().getIpv6PrefixLength()).isEqualTo(80);
  }

  @Test
  void rejectsInvalidRequestBodyLimit() {
    assertThatThrownBy(
            () ->
                bind(
                    Map.of(
                        "PAYGATE_REQUEST_BODY_MAX_BYTES", "0",
                        "PAYGATE_RATE_LIMIT_IPV6_PREFIX_LENGTH", "64")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasStackTraceContaining("paygate.request-body.max-bytes must be between");
  }

  @Test
  void rejectsInvalidIpv6PrefixLength() {
    assertThatThrownBy(
            () ->
                bind(
                    Map.of(
                        "PAYGATE_REQUEST_BODY_MAX_BYTES", "8192",
                        "PAYGATE_RATE_LIMIT_IPV6_PREFIX_LENGTH", "129")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasStackTraceContaining("paygate.rate-limit.ipv6-prefix-length must be between");
  }

  private PaygateProperties bind(Map<String, Object> environment) throws IOException {
    MutablePropertySources propertySources = new MutablePropertySources();
    propertySources.addFirst(
        new MapPropertySource("paygateSecurityEnvironment", environment));
    for (var propertySource :
        new YamlPropertySourceLoader()
            .load(
                "applicationConfig",
                new FileSystemResource("src/main/resources/application.yml"))) {
      propertySources.addLast(propertySource);
    }

    PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);
    MapPropertySource resolvedSecurityProperties =
        new MapPropertySource(
            "resolvedPaygateSecurityProperties",
            Map.of(
                "paygate.request-body.max-bytes",
                resolver.getRequiredProperty("paygate.request-body.max-bytes"),
                "paygate.rate-limit.ipv6-prefix-length",
                resolver.getRequiredProperty("paygate.rate-limit.ipv6-prefix-length")));

    return new Binder(ConfigurationPropertySources.from(resolvedSecurityProperties))
        .bind("paygate", Bindable.of(PaygateProperties.class))
        .get();
  }
}
