package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review R-03 of U9: the {@code Last-Event-ID} the app sends back is read before a channel slot is taken; a value that
 * is not one of ours, or whose numbers do not fit in a {@code long}, is treated as absent instead of failing.
 */
@Tag("u9")
class ResumeMarksTest {

    @Test
    void readsTheThreeMarksOfAnIdOfTheChannel() {
        assertThat(DashboardMarkPoller.resumeMarks("b12.l3.s45"))
                .contains(new DashboardMarkPoller.ResumeMarks(12, 3, 45));
    }

    @Test
    void acceptsTheLargestLong() {
        String max = Long.toString(Long.MAX_VALUE);
        assertThat(DashboardMarkPoller.resumeMarks("b" + max + ".l0.s0"))
                .contains(new DashboardMarkPoller.ResumeMarks(Long.MAX_VALUE, 0, 0));
    }

    @Test
    void aNumberAboveLongMaxValueIsTreatedAsAbsent() {
        assertThat(DashboardMarkPoller.resumeMarks("b99999999999999999999.l1.s1"))
                .isEmpty();
        assertThat(DashboardMarkPoller.resumeMarks("b1.l99999999999999999999.s1"))
                .isEmpty();
        assertThat(DashboardMarkPoller.resumeMarks("b1.l1.s99999999999999999999"))
                .isEmpty();
    }

    @Test
    void anIdThatIsNotOursIsTreatedAsAbsent() {
        assertThat(DashboardMarkPoller.resumeMarks("")).isEmpty();
        assertThat(DashboardMarkPoller.resumeMarks("b1.l2")).isEmpty();
        assertThat(DashboardMarkPoller.resumeMarks("b-1.l2.s3")).isEmpty();
        assertThat(DashboardMarkPoller.resumeMarks("x1.l2.s3")).isEmpty();
    }
}
