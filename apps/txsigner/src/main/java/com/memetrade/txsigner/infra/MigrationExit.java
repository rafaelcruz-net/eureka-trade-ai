package com.memetrade.txsigner.infra;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In the migrate profile, Liquibase already ran during the start-up: the process exits with the context's code. */
@Component
@Profile("migrate")
public class MigrationExit implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    public MigrationExit(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.exit(SpringApplication.exit(context));
    }
}
