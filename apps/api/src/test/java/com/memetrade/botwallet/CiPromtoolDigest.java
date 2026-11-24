package com.memetrade.botwallet;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * 261121-paper-real-unico U4 (decision F1 of the founder; observability-design.md "T20"): the image of the promtool the
 * CI already uses, read from the step {@code Alert rules (promtool)} of the job {@code backend} of the ci.yml, so the
 * test of the rules runs exactly that image and the ci.yml stays the one place of the digest.
 */
final class CiPromtoolDigest {

    static final String STEP = "Alert rules (promtool)";
    private static final Pattern IMAGE = Pattern.compile("prom/prometheus@sha256:[0-9a-f]{64}");

    private CiPromtoolDigest() {}

    /**
     * The one image pinned by digest in the step.
     *
     * @throws IllegalStateException with the reason when the step is absent, or holds no digest or more than one
     */
    @SuppressWarnings("unchecked")
    static String of(String ciYaml) {
        Map<String, Object> document = new Yaml().load(ciYaml);
        Map<String, Object> jobs = (Map<String, Object>) document.get("jobs");
        Map<String, Object> backend = jobs == null ? null : (Map<String, Object>) jobs.get("backend");
        List<Map<String, Object>> steps = backend == null ? null : (List<Map<String, Object>>) backend.get("steps");
        if (steps == null) {
            throw new IllegalStateException("the ci.yml has no job backend with steps");
        }
        String run = steps.stream()
                .filter(step -> STEP.equals(step.get("name")))
                .map(step -> String.valueOf(step.get("run")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the job backend has no step '" + STEP + "'"));
        Set<String> images = new LinkedHashSet<>();
        Matcher image = IMAGE.matcher(run);
        while (image.find()) {
            images.add(image.group());
        }
        if (images.size() != 1) {
            throw new IllegalStateException("the step '" + STEP + "' must pin exactly 1 prom/prometheus digest, found "
                    + images.size() + ": " + images);
        }
        return images.iterator().next();
    }
}
