package com.memetrade.bots.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U3, Etapa 10 (decision F-2 of the NFR Design; NFR3.13, NFR3.17): the only read of the
 * directory outside the tenant of the caller answers only whether a bot of ANOTHER tenant has the id: {@code true} for a
 * bot of another tenant, {@code false} for an id that does not exist and for a bot of the caller's own tenant.
 */
@Tag("u3-pnl-views")
class BotDirectoryOwnedByAnotherTenantIT extends AbstractPaperRealIT {

    @Autowired
    private BotDirectory directory;

    @Test
    void aBotOfAnotherTenantIsTrue() throws Exception {
        Person owner = signIn(false, false);
        Person stranger = signIn(false, false);
        UUID bot = new PnlViewsRows(jdbc).realBot(owner.tenantId(), owner.userId(), "R", PnlViewsRows.STRATEGY);

        assertThat(directory.ownedByAnotherTenant(stranger.tenantId(), bot)).isTrue();
    }

    @Test
    void anIdThatDoesNotExistIsFalse() throws Exception {
        Person person = signIn(false, false);

        assertThat(directory.ownedByAnotherTenant(person.tenantId(), UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void aBotOfTheOwnTenantIsFalse() throws Exception {
        Person person = signIn(false, false);
        UUID bot = new PnlViewsRows(jdbc).paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);

        assertThat(directory.ownedByAnotherTenant(person.tenantId(), bot)).isFalse();
    }

    @Test
    void theMethodAnswersOnlyABoolean() throws Exception {
        Method method = BotDirectory.class.getMethod("ownedByAnotherTenant", UUID.class, UUID.class);

        assertThat(method.getReturnType()).isEqualTo(boolean.class);
    }
}
