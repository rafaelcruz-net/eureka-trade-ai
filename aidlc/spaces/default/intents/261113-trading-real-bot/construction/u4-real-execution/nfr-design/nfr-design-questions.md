# Perguntas de NFR Design — U4 `u4-real-execution`

Conversa: português (Brasil). Base:
- `construction/u4-real-execution/nfr-requirements/` (U4-NFR1.1 a U4-NFR10.1);
- `construction/u4-real-execution/functional-design/` (BR1.1 a BR10.2);
- o código atual:
  - `apps/txsigner`, que já tem o leitor de mensagem da política de saque;
  - `botwallet.domain.Base58Codec`;
  - `RpcChainWatcher`, com dois provedores;
  - o canal ao vivo do módulo `bots`;
  - `RuntimeSettingsGuard`;
  - o padrão da U3 (trava da pessoa, compare-and-set, ShedLock).

## Q1. [Condutor da ordem] Como os trabalhos avançam a ordem sem duas instâncias pisarem uma na outra?

A. Cada trabalho pega um lote pequeno de ordens no seu estado com `FOR UPDATE SKIP LOCKED` e marca uma posse curta (`leaseUntil`, agora + 10 s) numa transação curta. Faz a chamada de rede fora da transação e grava o resultado com compare-and-set sobre o estado e a posse. Se a instância cair, a posse vence e outra retoma do estado gravado. Os trabalhos rodam no worker; o `POST` da sessão de assinatura roda na API, com a mesma posse.
B. Uma única instância do worker, sem posse
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Mensagem Solana] Onde fica o código que monta e lê a mensagem v0?

A. Num módulo Maven compartilhado novo, `libs/solana-tx`, sem rede e sem dependência de terceiros, usado pela API e pelo TxSigner. Ele contém:
- Base58;
- serialização compacta;
- montagem e leitura da mensagem v0 só com chaves estáticas;
- o resumo SHA-256;
- a conferência Ed25519 pelo Java nativo.

O leitor que o TxSigner já tem e o `Base58Codec` da U1 passam a usar esse módulo. Os vetores de teste são gerados pelo SDK Solana, só no escopo de teste.
B. Copiar o código nos dois aplicativos
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Jupiter] Como é o cliente?

A. O `JupiterClient` em `realexecution.infra.jupiter` usa duas rotas:
- **Cotação:** com `slippageBps` e `maxAccounts`.
- **Instruções de swap:** com a carteira do bot como dona e, no modo Assinada, a carteira principal como pagadora.

Cada chamada tem tempo limite de 2 s e um circuito Resilience4j próprio. Os valores são lidos como inteiros ou `BigDecimal`, e as respostas inválidas viram falha da ordem. Os testes usam respostas gravadas com WireMock.
B. Montar a rota sem a Jupiter
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Cossinatura] Quem confere a assinatura da carteira principal?

A. Os dois:
- **A API**, primeiro e de forma barata: o resumo da mensagem igual ao gravado, a assinatura Ed25519 válida da carteira principal registrada e o prazo.
- **O TxSigner**, de novo e independente, dentro da política BR4.3.

A API nunca confia no navegador, e o TxSigner nunca confia na API.
B. Só o TxSigner confere
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Eventos] Quando o canal ao vivo e a auditoria recebem a mudança?

A. A auditoria entra na mesma transação da mudança de estado, pela saída que já existe (propagação `MANDATORY`). O aviso no canal ao vivo é publicado depois do commit (`AFTER_COMMIT`), para nunca avisar uma mudança desfeita. O canal é o do módulo `bots`, com o tipo de evento novo da U4, filtrado por tenant e usuário.
B. Avisar antes do commit
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [P&L] Calcular na leitura ou guardar?

A. Calcular na leitura, a partir dos lançamentos REAL das ordens confirmadas (M2) e do custo médio por mint, com os preços em lote da M4. Com o volume do piloto (até 50 posições), o cálculo fica dentro dos 300 ms. O cálculo é uma classe de domínio pura, testada antes do código com cenários de resultado conhecido.
B. Guardar uma tabela de P&L atualizada a cada ordem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Trava da pessoa] Onde a U4 usa a trava da U3?

A. A trava da pessoa da U3 (pela M5) é usada:
- no consumidor da fila, antes do `handOver`;
- na criação da liquidação;
- na troca de modo.

Assim, o cancelamento das ordens não enviadas e a criação de ordens nunca correm juntos para a mesma pessoa. A M5 ganha `withPersonLock(tenantId, userId, ação)`, sem expor a classe interna da trava.
B. Uma trava nova só da U4
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Trabalhos com `SKIP LOCKED` e posse de 10 s; a rede fica fora da transação e o resultado é gravado com compare-and-set; outra instância retoma depois de uma queda [Q1].
- Módulo compartilhado `libs/solana-tx` para a mensagem, o Base58 e o Ed25519, usado pela API e pelo TxSigner [Q2].
- Cliente da Jupiter com cotação e instruções de swap, tempo limite de 2 s e circuito [Q3].
- A cossinatura é conferida pela API e de novo pelo TxSigner [Q4].
- Auditoria na transação; aviso no canal depois do commit [Q5].
- P&L calculado na leitura, por uma classe pura testada antes do código [Q6].
- A trava da pessoa da U3, pela M5, serializa a fila, a liquidação e a troca de modo [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
