package com.greenharborlabs.paygate.reference.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {
  @Test
  void mapsApiProblemToProblemDetailsContract() {
    var handler = new ApiExceptionHandler();
    var pd = handler.handle(new ApiProblem("UNSAFE_TARGET", HttpStatus.BAD_REQUEST, false, "Unsafe target."));
    assertThat(pd.getStatus()).isEqualTo(400);
    assertThat(pd.getProperties()).containsEntry("code", "UNSAFE_TARGET");
    assertThat(pd.getProperties()).containsEntry("retryable", false);
    assertThat(pd.getType().toString()).contains("/unsafe_target");
  }
}

