package com.memetrade.botwallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Base of the REAL integration tests of the api: a signed-in person, the fake TxSigner and the REAL seeds. */
@Import(AbstractRealIT.FakeSignerConfig.class)
public abstract class AbstractRealIT extends AbstractPostgresIT {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected FakeTxSigner signer;

    @Autowired
    protected org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeEach
    void resetSigner() {
        signer.reset();
    }

    /** A signed-in person with its main wallet (generated in test code only). */
    protected record Person(String bearer, UUID tenantId, UUID userId, TestWallet wallet) {}

    protected Person signIn(boolean acceptRealTerms, boolean released) throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        if (acceptRealTerms) {
            TestAccounts.acceptTerms(jdbc, seeded.tenantId(), seeded.userId(), "v3");
        }
        if (released) {
            release(seeded.userId());
        }
        AuthFlow auth = new AuthFlow(mvc, json);
        return new Person("Bearer " + auth.accessToken(auth.login(wallet)), seeded.tenantId(), seeded.userId(), wallet);
    }

    /**
     * BR7.1: a release requested 25 hours ago on the network of the environment, ACTIVE since the U5 (the gates turn a
     * release ACTIVE after its quarantine; only an ACTIVE one is in force).
     */
    protected void release(UUID userId) {
        var requested = clock.instant().minus(Duration.ofHours(25));
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state)
                        values (:id, :user, 'LOCALNET', :user, :requested, :effective, 'ACTIVE')""")
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("requested", Timestamp.from(requested))
                .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                .update();
    }

    protected MockHttpServletResponse call(Person person, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();
    }

    protected JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    @TestConfiguration
    public static class FakeSignerConfig {

        @Bean
        FakeTxSigner fakeTxSigner() {
            return new FakeTxSigner();
        }

        @Bean
        @Primary
        TxSignerPort testTxSigner(FakeTxSigner fake) {
            return fake;
        }
    }
}
