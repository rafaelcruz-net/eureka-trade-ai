package com.memetrade.realexecution.exitsfixture;

import com.memetrade.realexits.domain.ConditionalState;

/**
 * Test-only fixture of the U7 (Step 1): a class of {@code realexecution} that imports {@code realexits}, which the
 * extension interfaces exist to avoid (logical-components.md, Q2). Never part of the production code.
 */
public final class RealExitsImportFixture {

    public boolean open(ConditionalState state) {
        return state == ConditionalState.OPEN;
    }
}
