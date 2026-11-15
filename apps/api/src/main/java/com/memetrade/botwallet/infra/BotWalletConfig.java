package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainUnavailableException;
import com.memetrade.botwallet.domain.DepositScanner;
import com.memetrade.botwallet.domain.DepositStore;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.botwallet.domain.WithdrawalProcessor;
import com.memetrade.botwallet.domain.WithdrawalSettings;
import com.memetrade.botwallet.domain.WithdrawalStore;
import com.memetrade.botwallet.infra.chain.RpcBudget;
import com.memetrade.botwallet.infra.chain.RpcChainWatcher;
import com.memetrade.botwallet.infra.chain.SolanaRpcProvider;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the bot wallet: the TxSigner client, the ChainWatcher of the worker and the worker rules. */
@Configuration(proxyBeanMethods = false)
public class BotWalletConfig {

    @Bean
    TxSignerPort txSigner(RealProperties properties) {
        return new HttpTxSignerClient(
                HttpClient.newBuilder()
                        .connectTimeout(properties.txsigner().timeout())
                        .build(),
                URI.create(properties.txsigner().url()),
                properties.txsigner().token(),
                properties.txsigner().timeout());
    }

    /** Only the worker calls the RPC; without a configured provider every call is "unavailable" (the worker waits). */
    @Bean
    ChainPort chainPort(RealProperties properties, Clock clock) {
        List<SolanaRpcProvider> providers = new ArrayList<>();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.rpc().timeout())
                .build();
        addProvider(providers, "primary", properties.rpc().primaryUrl(), http, properties);
        addProvider(providers, "secondary", properties.rpc().secondaryUrl(), http, properties);
        if (providers.isEmpty()) {
            return (ChainPort) Proxy.newProxyInstance(
                    ChainPort.class.getClassLoader(), new Class<?>[] {ChainPort.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(this, args);
                        }
                        throw new ChainUnavailableException("No RPC provider configured");
                    });
        }
        return new RpcChainWatcher(
                providers, new RpcBudget(clock, properties.rpc().maxRps()));
    }

    private static void addProvider(
            List<SolanaRpcProvider> providers, String name, String url, HttpClient http, RealProperties properties) {
        if (url != null && !url.isBlank()) {
            providers.add(new SolanaRpcProvider(
                    name, URI.create(url), http, properties.rpc().timeout()));
        }
    }

    /**
     * 261113-trading-real-bot U4 (M3 for the REAL execution): the swap reads of the same ChainWatcher; without a
     * configured provider every call is "unavailable".
     */
    @Bean
    com.memetrade.botwallet.domain.SwapChain swapChain(ChainPort chain) {
        if (chain instanceof com.memetrade.botwallet.domain.SwapChain swaps) {
            return swaps;
        }
        return (com.memetrade.botwallet.domain.SwapChain) Proxy.newProxyInstance(
                ChainPort.class.getClassLoader(),
                new Class<?>[] {com.memetrade.botwallet.domain.SwapChain.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    throw new ChainUnavailableException("No RPC provider configured");
                });
    }

    @Bean
    WithdrawalProcessor withdrawalProcessor(
            Clock clock, WithdrawalStore store, TxSignerPort signer, ChainPort chain, RealProperties properties) {
        return new WithdrawalProcessor(
                clock,
                store,
                signer,
                chain,
                new WithdrawalSettings(
                        properties.computeUnitLimit(),
                        properties.computeUnitPrice(),
                        properties.jobs().withdrawalBatchSize(),
                        properties.jobs().withdrawalLease()));
    }

    @Bean
    DepositScanner depositScanner(ChainPort chain, DepositStore store) {
        return new DepositScanner(chain, store);
    }
}
