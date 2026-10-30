package com.memetrade;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the MemeTrade API process (modular monolith: shared, identity, marketdata, scanner, audit). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MemetradeApplication {

    public static void main(String[] args) {
        // Instants are stored and transmitted in UTC (project.md); the JVM default never leaks into the domain.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(MemetradeApplication.class, args);
    }
}
