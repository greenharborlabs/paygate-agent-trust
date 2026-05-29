package com.greenharborlabs.paygate.reference;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaygateReferenceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PaygateReferenceApplication.class, args);
  }
}
