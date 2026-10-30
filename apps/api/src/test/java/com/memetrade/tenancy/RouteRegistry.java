package com.memetrade.tenancy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads {@code tenancy/route-registry.yml} and the routes and scheduled jobs that actually exist, so tests can fail
 * the build when a route or job is unclassified or a CLIENT entry has no cross-tenant negative test (BR2.3).
 */
public final class RouteRegistry {

    public record Entry(String key, String scope, String negativeTest) {}

    private final List<Entry> routes;
    private final List<Entry> jobs;

    private RouteRegistry(List<Entry> routes, List<Entry> jobs) {
        this.routes = routes;
        this.jobs = jobs;
    }

    @SuppressWarnings("unchecked")
    public static RouteRegistry load() throws IOException {
        try (InputStream in = new ClassPathResource("tenancy/route-registry.yml").getInputStream()) {
            Map<String, Object> yaml = new Yaml().load(in);
            List<Entry> routes = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) yaml.get("routes")) {
                routes.add(new Entry(r.get("method") + " " + r.get("path"), (String) r.get("scope"), (String)
                        r.get("negativeTest")));
            }
            List<Entry> jobs = new ArrayList<>();
            for (Map<String, Object> j : (List<Map<String, Object>>) yaml.get("jobs")) {
                jobs.add(new Entry((String) j.get("name"), (String) j.get("scope"), (String) j.get("negativeTest")));
            }
            return new RouteRegistry(routes, jobs);
        }
    }

    public static RouteRegistry of(List<Entry> routes, List<Entry> jobs) {
        return new RouteRegistry(routes, jobs);
    }

    public List<Entry> routes() {
        return routes;
    }

    public List<Entry> jobs() {
        return jobs;
    }

    /** CLIENT routes and jobs whose declared negative test does not exist on the test classpath. */
    public List<String> clientEntriesWithoutNegativeTest() {
        List<String> missing = new ArrayList<>();
        for (Entry entry : concat()) {
            if ("CLIENT".equals(entry.scope()) && !testExists(entry.negativeTest())) {
                missing.add(entry.key());
            }
        }
        return missing;
    }

    /** ADMIN routes (U2) whose declared negative test (a guest token gets 403) does not exist. */
    public List<String> adminEntriesWithoutNegativeTest() {
        List<String> missing = new ArrayList<>();
        for (Entry entry : routes) {
            if ("ADMIN".equals(entry.scope()) && !testExists(entry.negativeTest())) {
                missing.add(entry.key());
            }
        }
        return missing;
    }

    /** "METHOD /path" of every endpoint declared by the application's REST controllers. */
    public static Set<String> declaredRoutes() throws ClassNotFoundException {
        Set<String> found = new LinkedHashSet<>();
        for (Class<?> controller : scan(RestController.class)) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = mapping.path().length == 0 ? "" : mapping.path()[0];
                Arrays.stream(mapping.method()).forEach(m -> found.add(m.name() + " " + prefix + path));
            }
        }
        return found;
    }

    /** Simple names of every application component with a {@code @Scheduled} method. */
    public static Set<String> declaredJobs() throws ClassNotFoundException {
        Set<String> found = new LinkedHashSet<>();
        for (Class<?> component : scan(Component.class)) {
            for (Method method : component.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    found.add(component.getSimpleName());
                }
            }
        }
        return found;
    }

    private List<Entry> concat() {
        List<Entry> all = new ArrayList<>(routes);
        all.addAll(jobs);
        return all;
    }

    private static boolean testExists(String reference) {
        if (reference == null || !reference.contains("#")) {
            return false;
        }
        String[] parts = reference.split("#", 2);
        try {
            return Arrays.stream(Class.forName(parts[0]).getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals(parts[1]));
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private static List<Class<?>> scan(Class<? extends java.lang.annotation.Annotation> annotation)
            throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.memetrade")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (!type.getName().contains("Test") && !type.getName().endsWith("IT")) {
                classes.add(type);
            }
        }
        return classes;
    }
}
