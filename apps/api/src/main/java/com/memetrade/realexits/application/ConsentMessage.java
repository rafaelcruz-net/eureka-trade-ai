package com.memetrade.realexits.application;

import com.memetrade.realexits.domain.TriggerPort;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * BR1.2, infrastructure-specification.md "Caminho simulado": the consent of the conditional the main wallet signs with
 * {@code signMessage} when the adapter takes no co-signed transaction. Built by the server, shown and signed unchanged
 * by the wallet, then compared byte by byte by the native Ed25519 of the identity (M9). It names the network, the
 * conditional and everything it does, so a signature never serves another conditional, another network nor a login.
 * Pure text in Portuguese (the person reads it in the wallet); no address of the person.
 */
final class ConsentMessage {

    private ConsentMessage() {}

    static String of(String network, TriggerPort.CreateRequest request, Instant deadline) {
        StringBuilder text = new StringBuilder()
                .append("Eureka Trader - saída automática REAL\n")
                .append("Rede: ")
                .append(network)
                .append('\n')
                .append("Condicional: ")
                .append(request.conditionalOrderId())
                .append('\n')
                .append("Token: ")
                .append(request.mint())
                .append('\n')
                .append("Tipo: ")
                .append(request.kind().name())
                .append('\n');
        request.stopPriceUsd()
                .ifPresent(price ->
                        text.append("Stop (USD): ").append(plain(price)).append('\n'));
        request.takeProfitPriceUsd()
                .ifPresent(price ->
                        text.append("Alvo (USD): ").append(plain(price)).append('\n'));
        return text.append("Quantidade (unidades base): ")
                .append(request.amountBaseUnits())
                .append('\n')
                .append("Derrapagem máxima (bps): ")
                .append(request.slippageBps())
                .append('\n')
                .append("Válido até: ")
                .append(deadline)
                .append('\n')
                .append("O stop não tem execução garantida. O resultado da venda volta só para a carteira do bot.")
                .toString();
    }

    private static String plain(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }
}
