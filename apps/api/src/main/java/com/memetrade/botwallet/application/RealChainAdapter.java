package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainUnavailableException;
import com.memetrade.botwallet.domain.SwapChain;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * M3 for the REAL execution (261113-trading-real-bot U4): the ChainWatcher of the bot wallet behind the public
 * interface {@link RealChain}. Every unavailability of the providers becomes {@link RealUpstreamUnavailable} ({@code
 * RPC}); the RPC endpoints, which may carry a key, are never logged.
 */
@Service
public class RealChainAdapter implements RealChain {

    private final ChainPort chain;
    private final SwapChain swaps;

    /**
     * CI correction (run 43769144828): the swap reads come from the one ChainPort bean (the ChainWatcher with the RPC
     * configured is both), never from a second bean, which made ChainPort and SwapChain ambiguous in the worker.
     */
    public RealChainAdapter(ChainPort chain) {
        this.chain = chain;
        this.swaps = SwapChain.of(chain);
    }

    @Override
    public Blockhash latestBlockhash() {
        ChainPort.RecentBlockhash recent = call(chain::latestBlockhash);
        return new Blockhash(recent.blockhash(), recent.lastValidBlockHeight());
    }

    @Override
    public long blockHeight() {
        return call(swaps::blockHeight);
    }

    @Override
    public String send(String signedTransactionBase64) {
        return call(() -> chain.send(signedTransactionBase64));
    }

    @Override
    public TxStatus status(String txSignature) {
        SwapChain.SignatureStatus status = call(() -> swaps.status(txSignature));
        return switch (status) {
            case FINALIZED -> TxStatus.of(TxStatus.Kind.FINALIZED);
            case FAILED -> new TxStatus(TxStatus.Kind.FAILED, false);
            case FAILED_SLIPPAGE -> new TxStatus(TxStatus.Kind.FAILED, true);
            case PENDING -> TxStatus.of(TxStatus.Kind.PENDING);
            case NOT_FOUND -> TxStatus.of(TxStatus.Kind.NOT_FOUND);
        };
    }

    @Override
    public SwapEffect swapEffect(String txSignature, String botWalletAddress, String mint) {
        SwapChain.Effect effect = call(() -> swaps.effect(txSignature, botWalletAddress, mint));
        return new SwapEffect(
                effect.ownerLamportsDelta(),
                effect.tokenBaseUnitsDelta(),
                effect.feeLamports(),
                effect.feePaidByOwner(),
                effect.slot());
    }

    private static <T> T call(Supplier<T> read) {
        try {
            return read.get();
        } catch (ChainUnavailableException down) {
            throw new RealUpstreamUnavailable(RealUpstreamUnavailable.Provider.RPC);
        } catch (RuntimeException refused) {
            if (refused.getClass().getName().endsWith("RpcRefusedException")
                    || refused.getClass().getName().endsWith("RpcUnavailableException")) {
                throw new RealUpstreamUnavailable(RealUpstreamUnavailable.Provider.RPC);
            }
            throw refused;
        }
    }
}
