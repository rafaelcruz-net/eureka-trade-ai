package com.memetrade.pnl.real;

import com.memetrade.ledger.api.CostHolder;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (ENT-205, BR3.1, BR3.3, BR4.2): the holder of a cost position inside a bot wallet: a REAL
 * bot of the person, whatever its state (a paused or removed bot keeps its lots), or the part without owner. Its order
 * is the tie-break of the largest remainder: bots by creation (older first), then by the canonical lower-case text of
 * the id compared character by character; UNOWNED always last.
 *
 * @param bot the bot; {@code null} only for {@link #UNOWNED}
 * @param botCreatedAt when the bot was created (from the directory, C8); {@code null} only for {@link #UNOWNED}
 */
public record Holder(UUID bot, Instant botCreatedAt) implements CostHolder {

    /** The part without owner: buys without a resolved bot, purged orders and what a sale took beyond every position. */
    public static final Holder UNOWNED = new Holder(null, null);

    public Holder {
        if ((bot == null) != (botCreatedAt == null)) {
            throw new IllegalArgumentException("a bot holder has its id and its creation; UNOWNED has neither");
        }
    }

    public static Holder bot(UUID bot, Instant createdAt) {
        return new Holder(Objects.requireNonNull(bot, "bot"), Objects.requireNonNull(createdAt, "createdAt"));
    }

    @Override
    public boolean unowned() {
        return bot == null;
    }

    public Optional<UUID> botId() {
        return Optional.ofNullable(bot);
    }

    @Override
    public int compareTo(CostHolder other) {
        Holder that = (Holder) other;
        if (unowned() || that.unowned()) {
            return Boolean.compare(unowned(), that.unowned());
        }
        int order = botCreatedAt.compareTo(that.botCreatedAt);
        if (order != 0) {
            return order;
        }
        return bot.toString()
                .toLowerCase(Locale.ROOT)
                .compareTo(that.bot.toString().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return unowned() ? "Holder[UNOWNED]" : "Holder[bot=" + bot + "]";
    }
}
