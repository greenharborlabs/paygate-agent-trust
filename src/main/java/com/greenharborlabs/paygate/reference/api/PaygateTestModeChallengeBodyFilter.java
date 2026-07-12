package com.greenharborlabs.paygate.reference.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaygateTestModeChallengeBodyFilter extends OncePerRequestFilter {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {};

  private final boolean testMode;

  public PaygateTestModeChallengeBodyFilter(@Value("${paygate.test-mode:false}") boolean testMode) {
    this.testMode = testMode;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!testMode) {
      filterChain.doFilter(request, response);
      return;
    }

    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    filterChain.doFilter(request, wrapper);

    byte[] body = wrapper.getContentAsByteArray();
    if (!shouldRewrite(wrapper, body)) {
      wrapper.copyBodyToResponse();
      return;
    }

    Map<String, Object> challengeBody = MAPPER.readValue(body, BODY_TYPE);
    Object priceSats = challengeBody.get("price_sats");
    if (priceSats != null) {
      Map<String, Object> rewritten = new LinkedHashMap<>(challengeBody);
      rewritten.putIfAbsent("amount_sats", priceSats);
      rewritten.putIfAbsent("amountSats", priceSats);
      body = MAPPER.writeValueAsBytes(rewritten);
      wrapper.resetBuffer();
      wrapper.setContentLength(body.length);
      wrapper.getOutputStream().write(body);
    }

    wrapper.copyBodyToResponse();
  }

  private static boolean shouldRewrite(ContentCachingResponseWrapper response, byte[] body) {
    String contentType = response.getContentType();
    return response.getStatus() == HttpStatus.PAYMENT_REQUIRED.value()
        && body.length > 0
        && contentType != null
        && contentType.toLowerCase(Locale.ROOT).contains("application/json");
  }
}
