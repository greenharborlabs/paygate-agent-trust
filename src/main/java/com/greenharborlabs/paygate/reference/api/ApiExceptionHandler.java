package com.greenharborlabs.paygate.reference.api;

import java.net.URI;
import java.util.Map;
import java.util.Locale;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiProblem.class)
  public ProblemDetail handle(ApiProblem ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
    pd.setTitle(ex.code().replace('_', ' '));
    pd.setType(
        URI.create(
            "https://paygate-reference.greenharborlabs.com/problems/"
                + ex.code().toLowerCase(Locale.ROOT)));
    pd.setProperty("code", ex.code());
    pd.setProperty("retryable", ex.retryable());
    return pd;
  }
}
