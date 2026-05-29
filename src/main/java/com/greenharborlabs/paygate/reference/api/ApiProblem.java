package com.greenharborlabs.paygate.reference.api;

import org.springframework.http.HttpStatus;

public class ApiProblem extends RuntimeException {
  private final String code;
  private final HttpStatus status;
  private final boolean retryable;

  public ApiProblem(String code, HttpStatus status, boolean retryable, String message) {
    super(message);
    this.code = code;
    this.status = status;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }
}
