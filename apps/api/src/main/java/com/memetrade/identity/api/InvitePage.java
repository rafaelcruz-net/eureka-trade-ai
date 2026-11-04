package com.memetrade.identity.api;

import java.util.List;

/** One page of the invitation list (C17 {@code Page<Invite>}); {@code nextCursor} is {@code null} on the last page. */
public record InvitePage(List<InviteView> items, String nextCursor) {

    public InvitePage {
        items = List.copyOf(items);
    }
}
