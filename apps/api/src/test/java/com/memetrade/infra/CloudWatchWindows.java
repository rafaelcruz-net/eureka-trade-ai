package com.memetrade.infra;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Observability Setup (alarms.md, section 1): the CloudWatch Agent reads /actuator/prometheus every 120 s, so a
 * CloudWatch alarm window cannot always equal the window of its Prometheus rule. The *AlarmParityTest classes compare
 * the CloudWatch window with {@link #cloudWindow(long, boolean)}:
 *
 * <ul>
 *   <li>an alarm that treats missing data as breaching, and every stalled-job alarm, needs two reads in each period:
 *       the Prometheus window rounded up to a multiple of 240 s;
 *   <li>any other alarm needs one read in each period: the Prometheus window, or 180 s when it is shorter.
 * </ul>
 *
 * A threshold is never changed by this rule; only the window grows to hold the reads.
 */
public final class CloudWatchWindows {

    public static final long READ_INTERVAL_SECONDS = 120;
    public static final long TWO_READS_SECONDS = 2 * READ_INTERVAL_SECONDS;
    public static final long ONE_READ_MINIMUM_SECONDS = 180;

    private CloudWatchWindows() {}

    /** CloudWatch window of an alarm whose Prometheus rule looks at {@code prometheusWindow} seconds. */
    public static long cloudWindow(long prometheusWindow, boolean twoReads) {
        if (twoReads) {
            return (prometheusWindow + TWO_READS_SECONDS - 1) / TWO_READS_SECONDS * TWO_READS_SECONDS;
        }
        long minutes = (Math.max(prometheusWindow, ONE_READ_MINIMUM_SECONDS) + 59) / 60;
        return minutes * 60;
    }

    /**
     * Whether the alarm {@code <name>} of the Terraform text needs two reads per period: a stalled-job alarm, or an alarm
     * whose block treats missing data as breaching.
     */
    public static boolean twoReads(String terraform, String kebabName) {
        if (kebabName.contains("job-stalled")) {
            return true;
        }
        Matcher name = Pattern.compile("alarm_name\\s*=\\s*\"\\$\\{var\\.name}-" + Pattern.quote(kebabName) + "\"")
                .matcher(terraform);
        if (!name.find()) {
            return false;
        }
        int start = terraform.lastIndexOf("resource \"aws_cloudwatch_metric_alarm\"", name.start());
        int end = terraform.indexOf("\nresource ", name.end());
        String block = terraform.substring(start, end < 0 ? terraform.length() : end);
        return Pattern.compile("treat_missing_data\\s*=\\s*\"breaching\"")
                .matcher(block)
                .find();
    }
}
