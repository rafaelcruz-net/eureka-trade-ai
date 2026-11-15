package com.memetrade.txsigner;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the TxSigner process (ADR-010): the only process that holds the credential to sign with the bot
 * wallet keys in the KMS. It never generates, reads, imports nor exports a private key.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TxSignerApplication {

    public static void main(String[] args) {
        // Instants are stored and transmitted in UTC (project.md).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(TxSignerApplication.class, args);
    }
}
