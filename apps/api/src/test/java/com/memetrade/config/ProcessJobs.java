package com.memetrade.config;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finds, in a running context, every application bean that works on its own (infrastructure-specification.md
 * "Trabalhos por processo"): a {@code @Scheduled} method, an {@code ApplicationReadyEvent} listener, a
 * {@code CommandLineRunner} or {@code ApplicationRunner}, or a {@code SmartLifecycle}; and every controller.
 */
final class ProcessJobs {

    private ProcessJobs() {}

    /** Simple names of the application job beans of {@code context}. */
    static Set<String> jobs(ApplicationContext context) {
        Set<String> found = new TreeSet<>();
        for (Class<?> type : applicationTypes(context)) {
            if (isJob(type)) {
                found.add(type.getSimpleName());
            }
        }
        return found;
    }

    /** Simple names of the application job beans that do NOT carry {@code @ProcessRole}. */
    static Set<String> jobsWithoutRole(ApplicationContext context) {
        Set<String> missing = new TreeSet<>();
        for (Class<?> type : applicationTypes(context)) {
            if (isJob(type) && !type.isAnnotationPresent(com.memetrade.shared.config.ProcessRole.class)) {
                missing.add(type.getSimpleName());
            }
        }
        return missing;
    }

    /** Simple names of every job class of the code (classpath scan of components), active or not in this run. */
    static Set<String> jobClasses() throws ClassNotFoundException {
        Set<String> found = new TreeSet<>();
        for (Class<?> type : scannedComponents()) {
            if (isJob(type)) {
                found.add(type.getSimpleName());
            }
        }
        return found;
    }

    /** Job classes of the code without {@code @ProcessRole} (a new job must say where it runs). */
    static Set<String> jobClassesWithoutRole() throws ClassNotFoundException {
        Set<String> missing = new TreeSet<>();
        for (Class<?> type : scannedComponents()) {
            if (isJob(type) && !type.isAnnotationPresent(com.memetrade.shared.config.ProcessRole.class)) {
                missing.add(type.getSimpleName());
            }
        }
        return missing;
    }

    private static java.util.List<Class<?>> scannedComponents() throws ClassNotFoundException {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                org.springframework.stereotype.Component.class));
        java.util.List<Class<?>> classes = new java.util.ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.memetrade")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (!type.getName().contains("Test")
                    && !type.getName().endsWith("IT")
                    && !type.getName().startsWith("com.memetrade.testsupport")) {
                classes.add(type);
            }
        }
        return classes;
    }

    static Set<String> controllers(ApplicationContext context) {
        Set<String> found = new TreeSet<>();
        for (Class<?> type : applicationTypes(context)) {
            if (AnnotatedElementUtils.hasAnnotation(type, RestController.class)) {
                found.add(type.getSimpleName());
            }
        }
        return found;
    }

    private static Set<Class<?>> applicationTypes(ApplicationContext context) {
        Set<Class<?>> types = new java.util.LinkedHashSet<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> declared = context.getType(name);
            if (declared == null) {
                continue;
            }
            Class<?> type = org.springframework.util.ClassUtils.getUserClass(declared);
            if (type.getName().startsWith("com.memetrade.") && !type.getName().contains("Test")) {
                types.add(type);
            }
        }
        return types;
    }

    private static boolean isJob(Class<?> type) {
        if (CommandLineRunner.class.isAssignableFrom(type)
                || ApplicationRunner.class.isAssignableFrom(type)
                || SmartLifecycle.class.isAssignableFrom(type)) {
            return true;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Scheduled.class) || listensToReady(method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean listensToReady(Method method) {
        EventListener listener = method.getAnnotation(EventListener.class);
        if (listener == null) {
            return false;
        }
        boolean declared = Arrays.asList(listener.classes()).contains(ApplicationReadyEvent.class)
                || Arrays.asList(listener.value()).contains(ApplicationReadyEvent.class);
        boolean parameter =
                method.getParameterCount() == 1 && method.getParameterTypes()[0] == ApplicationReadyEvent.class;
        return declared || parameter;
    }
}
