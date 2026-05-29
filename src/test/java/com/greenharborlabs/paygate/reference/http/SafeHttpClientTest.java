package com.greenharborlabs.paygate.reference.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class SafeHttpClientTest {
  private final SafeHttpClient client =
      new SafeHttpClient(
          new PaygateReferenceProperties(
              1, 2, 3, 6, 65536, 8192, 32, 15, 16, "MC4CAQAwBQYDK2VwBCIEIKFgoMB34QYC1lTcyWsgFIJcqRY2cNcV2dMHbGGmPvhD", "MCowBQYDK2VwAyEAgeVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM=", "test-key"));

  @Test
  void mapsIoToFetchFailed() throws Exception {
    assertThatThrownBy(
            () ->
                invokeMapException(
                    new IOException("io")))
        .isInstanceOf(ApiProblem.class)
        .satisfies(
            ex -> {
              ApiProblem problem = (ApiProblem) ex;
              assertThat(problem.code()).isEqualTo("TARGET_FETCH_FAILED");
            });
  }

  @Test
  void mapsTimeoutToTargetTimeout() throws Exception {
    assertThatThrownBy(() -> invokeMapException(new SocketTimeoutException("timeout")))
        .isInstanceOf(ApiProblem.class)
        .satisfies(ex -> assertThat(((ApiProblem) ex).code()).isEqualTo("TARGET_TIMEOUT"));
  }

  @Test
  void mapsTlsErrorToTargetTlsFailed() throws Exception {
    assertThatThrownBy(() -> invokeMapException(new SSLException("tls")))
        .isInstanceOf(ApiProblem.class)
        .satisfies(ex -> assertThat(((ApiProblem) ex).code()).isEqualTo("TARGET_TLS_FAILED"));
  }

  private void invokeMapException(Exception error) throws Exception {
    Method method = SafeHttpClient.class.getDeclaredMethod("mapException", Exception.class);
    method.setAccessible(true);
    RuntimeException mapped = (RuntimeException) method.invoke(client, error);
    throw mapped;
  }
}
