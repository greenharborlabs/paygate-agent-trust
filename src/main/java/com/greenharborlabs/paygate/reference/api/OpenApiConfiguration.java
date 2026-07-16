package com.greenharborlabs.paygate.reference.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "Paygate Agent Trust API",
            version = "0.1.2",
            description =
                "Public reference API for quoting, selling, signing, and verifying Paygate-backed agent trust reports.",
            contact = @Contact(name = "Green Harbor Labs"),
            license = @License(name = "Proprietary")),
    servers = {
      @Server(url = "http://localhost:8080", description = "Local development"),
      @Server(url = "https://paygate-agent-trust.fly.dev", description = "Deployed Fly app")
    })
@SecurityScheme(
    name = "paymentAuth",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = "Authorization",
    description = "Paygate credential header using the form `Authorization: Payment <credential>`.")
public class OpenApiConfiguration {}
