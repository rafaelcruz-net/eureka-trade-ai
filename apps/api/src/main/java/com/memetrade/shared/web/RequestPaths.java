package com.memetrade.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * The request path as the dispatcher sees it, for every decision a filter takes on a path (review finding "admin
 * route bypass"). {@link HttpServletRequest#getRequestURI()} is the raw, still encoded text the client sent:
 * {@code /api/v1/%61dmin/...} or {@code /api/v1/admin;x/...} do not start with {@code /api/v1/admin/}, yet Spring
 * decodes them, drops the {@code ;} content and routes them to the administrative controller. Here the path is
 * percent-decoded, stripped of {@code ;} content, with repeated slashes collapsed and {@code .}/{@code ..} segments
 * resolved.
 *
 * <p>Also builds the route recorded in audit events: the normalised path without query string, each segment that is
 * not a short plain word or a UUID replaced by {@link #REDACTED}, truncated to the audit column limit, so a
 * client-controlled path never carries a token, a signature or a wallet address into the trail and never makes the
 * audit publisher refuse the event.
 */
public final class RequestPaths {

    /** Marker that replaces a path segment that is not safe to record. */
    public static final String REDACTED = "{redacted}";

    /** Column limit of {@code audit_event.target_ref}. */
    public static final int MAX_ROUTE_LENGTH = 200;

    private static final String ADMIN_PREFIX = "/api/v1/admin";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_~-]{1,31}");
    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    private RequestPaths() {}

    /** Decoded, {@code ;}-free path with collapsed slashes and resolved dot segments; never the query string. */
    public static String normalized(HttpServletRequest request) {
        // defaultInstance decodes percent-escapes and removes ";" content, like the dispatcher's path lookup.
        String decoded = UrlPathHelper.defaultInstance.getRequestUri(request);
        String collapsed = REPEATED_SLASHES.matcher(decoded.replace('\\', '/')).replaceAll("/");
        String clean = StringUtils.cleanPath(collapsed);
        return clean.startsWith("/") ? clean : "/" + clean;
    }

    /**
     * True when the request reaches, or could reach, an administrative route ({@code /api/v1/admin} and below).
     * Case-insensitive on purpose: a spelling that only differs in case is treated as administrative too.
     */
    public static boolean isAdministrative(HttpServletRequest request) {
        String path = normalized(request).toLowerCase(Locale.ROOT);
        return path.equals(ADMIN_PREFIX) || path.startsWith(ADMIN_PREFIX + "/");
    }

    /** {@code METHOD /normalised/path} safe for {@code targetRef} and {@code details.route} of an audit event. */
    public static String auditRoute(HttpServletRequest request) {
        StringBuilder route = new StringBuilder(safeMethod(request.getMethod())).append(' ');
        String path = normalized(request);
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            route.append('/').append(safeSegment(segments[i]));
        }
        if (segments.length <= 1) {
            route.append('/');
        }
        return route.length() <= MAX_ROUTE_LENGTH ? route.toString() : route.substring(0, MAX_ROUTE_LENGTH - 3) + "...";
    }

    static String safeSegment(String segment) {
        if (segment.isEmpty()
                || SAFE_SEGMENT.matcher(segment).matches()
                || UUID_SEGMENT.matcher(segment).matches()) {
            return segment;
        }
        return REDACTED;
    }

    private static String safeMethod(String method) {
        return method != null && method.matches("[A-Z]{1,10}") ? method : "OTHER";
    }
}
