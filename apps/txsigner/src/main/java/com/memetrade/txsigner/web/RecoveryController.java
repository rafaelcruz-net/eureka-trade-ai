package com.memetrade.txsigner.web;

import com.memetrade.txsigner.domain.PolicyView;
import com.memetrade.txsigner.domain.RecoveryService;
import com.memetrade.txsigner.domain.RestoreWalletCommand;
import com.memetrade.txsigner.policy.OperationMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recovery routes (addendum of the contract R1, runbook docs/runbooks/txsigner-recovery.md): the api sends the main
 * wallet and the current lowered limits of each bot wallet; at the end it asks to complete the recovery.
 */
@RestController
@RequestMapping(path = "/internal/v1/recovery", produces = MediaType.APPLICATION_JSON_VALUE)
public class RecoveryController {

    private final RecoveryService recovery;

    public RecoveryController(RecoveryService recovery) {
        this.recovery = recovery;
    }

    record RestoreRequest(
            @NotNull @Pattern(regexp = InternalDtos.ADDRESS) String botWalletAddress,
            @NotNull @Pattern(regexp = InternalDtos.ADDRESS) String mainWalletAddress,
            @Pattern(regexp = InternalDtos.LAMPORTS) String currentCapLamports,
            OperationMode currentMode) {}

    @PostMapping("/wallets")
    public InternalDtos.PolicyResponse restore(@Valid @RequestBody RestoreRequest body) {
        PolicyView view = recovery.restore(new RestoreWalletCommand(
                body.botWalletAddress(),
                body.mainWalletAddress(),
                body.currentCapLamports() == null ? null : Long.parseLong(body.currentCapLamports()),
                body.currentMode()));
        return new InternalDtos.PolicyResponse(
                view.botWalletAddress(), Long.toString(view.capLamports()), view.mode(), view.closed());
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete() {
        recovery.complete();
        return ResponseEntity.noContent().build();
    }
}
