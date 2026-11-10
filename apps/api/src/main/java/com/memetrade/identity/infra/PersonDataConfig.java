package com.memetrade.identity.infra;

import com.memetrade.identity.api.AccountAnonymizationPort;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.domain.anonymization.AccountAnonymizationRepository;
import com.memetrade.identity.domain.anonymization.AccountAnonymizationService;
import com.memetrade.identity.domain.person.PersonDataRepository;
import com.memetrade.identity.domain.person.PersonDataService;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** J1 of u4-data-requests: the person data port Identity offers to Privacy. */
@Configuration(proxyBeanMethods = false)
public class PersonDataConfig {

    @Bean
    public PersonDataPort personDataPort(PersonDataRepository people, Ed25519SignatureVerifier verifier) {
        return new PersonDataService(people, verifier);
    }

    /** J1 of u5-anonymization: the anonymization of an account, only inside the transaction of Privacy. */
    @Bean
    public AccountAnonymizationPort accountAnonymizationPort(AccountAnonymizationRepository accounts) {
        return new AccountAnonymizationService(accounts);
    }
}
