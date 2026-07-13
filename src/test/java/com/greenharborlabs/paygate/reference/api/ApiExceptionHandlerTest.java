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

  @Test
  void mapsApiProblemWithCauseWithoutExposingCauseDetails() {
    var handler = new ApiExceptionHandler();
    var cause = new IllegalStateException("provider-secret-detail");
    var problem = new ApiProblem("UNSAFE_TARGET", HttpStatus.BAD_REQUEST, false, "Unsafe target.", cause);

    var pd = handler.handle(problem);

    assertThat(problem.getCause()).isSameAs(cause);
    assertThat(pd.getStatus()).isEqualTo(400);
    assertThat(pd.getDetail()).isEqualTo("Unsafe target.");
    assertThat(pd.getProperties()).containsEntry("code", "UNSAFE_TARGET");
    assertThat(pd.getProperties()).containsEntry("retryable", false);
    assertThat(pd.toString()).doesNotContain("provider-secret-detail");
  }
}
