package com.memetrade.identity.domain.session;

import com.memetrade.identity.api.Principal;
import java.util.UUID;

/** Principal of an authenticated request plus the session it came from (needed by logout). */
public record AuthenticatedSession(Principal principal, UUID sessionId) {}
