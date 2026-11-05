package com.memetrade.bots.application;

import com.memetrade.bots.domain.BotConfigValidator.RawLimits;
import com.memetrade.bots.domain.BotConfigValidator.RawSelection;
import java.util.Map;

/**
 * One accepted step of the wizard (C4 {@code PUT /bots/{botId}/draft}, BR1.1): step 1 carries parameters and selection,
 * step 2 the limits and the virtual balance, step 3 asks for the validation, step 4 records the start screen. Values
 * arrive as TEXT and are validated by the domain; fields of other steps are ignored by the step.
 */
public record DraftInput(
        int step,
        Map<String, String> parameters,
        RawSelection tokenSelection,
        RawLimits limits,
        String virtualBalance) {}
