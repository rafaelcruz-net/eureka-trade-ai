package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.api.EscrowBalances;
import com.memetrade.botwallet.api.ExitsAvailability;
import com.memetrade.botwallet.api.OpenExitsCheck;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    /**
     * 261113-trading-real-bot U7 (logical-components.md "Ajustes menores"): the empty implementations of the extension
     * interfaces of the M1, used only when the module {@code realexits} is not in the context (its components are
     * scanned before these methods are read, so they win): no custody, no open conditional, exits off.
     */
    @Bean
    @ConditionalOnMissingBean(EscrowBalances.class)
    EscrowBalances noEscrowBalances() {
        return (tenantId, botWalletId) -> List.of();
    }

    @Bean
    @ConditionalOnMissingBean(OpenExitsCheck.class)
    OpenExitsCheck noOpenExits() {
        return (tenantId, userId) -> false;
    }

    @Bean
    @ConditionalOnMissingBean(ExitsAvailability.class)
    ExitsAvailability exitsOff() {
        return () -> false;
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
