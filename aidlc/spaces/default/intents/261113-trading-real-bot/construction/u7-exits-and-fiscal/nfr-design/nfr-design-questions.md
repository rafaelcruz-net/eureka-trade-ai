# Perguntas de NFR Design — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Base:
- `construction/u7-exits-and-fiscal/functional-design/` (BR0 a BR9);
- `construction/u7-exits-and-fiscal/nfr-requirements/` (U7-NFR*);
- o código atual:
  - módulos `realexecution` (M6, `DecisionConsumer`, `LiquidationJob`), `botwallet` (M1, M7, `ReconciliationPolicy`, `closeForAnonymization`), `riskcontrol` (M5, `withPersonLock`) e `ledger` (M2, índice `real_ledger_entry_tenant_created_idx`);
  - TxSigner (`policy`, `SwapPolicy`, leitor);
  - ArchUnit;
- o grafo de dependências da Inception: a U7 depende de U1, U4 e U5, e nenhuma delas depende da U7.

## Q1. [Módulo] Onde mora a U7?

A. Um módulo novo, `realexits` (`api`, `domain`, `infra`, `infra.jupiter`, `web`), que fala com os outros só pelas interfaces públicas: M1, M2, M5, M6 e M7.

A exportação fiscal fica em `realexits.fiscal`. Ela lê o ledger por um método novo de leitura em fluxo da M2: `streamEntries(tenant, userId, from, to, consumer)`, por cursor JDBC e com o índice que já existe.

Novas regras de ArchUnit:
- `realexits` sem PAPER;
- rede só em `infra.jupiter`;
- nenhuma assinatura fora do TxSigner.
B. Dentro do `realexecution`
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Dependências sem ciclo] Como a U4 e a U5 consultam as condicionais sem depender da U7?

A. Por inversão de dependência, com interfaces de extensão declaradas pelos módulos donos e implementadas pela `realexits`:
- `realexecution.api.PositionExitGuard`: antes de toda venda a mercado (BR4.3), a U4 chama `releaseForSale(position)`. A U7 cancela a condicional, espera `CANCELLED` ou `FILLED` e devolve a posição relida no ledger.
- `botwallet.api.EscrowBalances`: a reconciliação (BR5.3b) pede o saldo das contas de custódia abertas por mint.
- `botwallet.api.OpenExitsCheck`: o `closeForAnonymization` (BR6.9) pergunta se há condicional não terminal.

Sem a U7 no contexto, cada interface tem uma implementação vazia que responde "nada aberto". Uma regra de ArchUnit garante que `realexecution` e `botwallet` não importam `realexits`.
B. A U4 e a U5 lendo a tabela da U7
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Concorrência] Como serializar condicional e venda?

A. Toda mudança de estado de uma condicional e toda venda a mercado da mesma pessoa rodam dentro do `withPersonLock` da M5, a trava por pessoa que a U4 já usa. As mudanças de estado são compare-and-set, e os trabalhos usam ShedLock com nome próprio.

O `PositionExitGuard` é chamado dentro da trava da venda. Se o cancelamento ainda não confirmou, ele devolve "aguarde" e a venda volta para a fila da U4, que já trata espera, em vez de travar a pessoa por minutos.
B. Trava própria da U7
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [TxSigner] Como a `TriggerPolicy` sabe o que foi gravado?

A. Um arquivo versionado no TxSigner, `trigger-program.json`, gerado pela gravação real (BR0.2), com:
- o id do programa;
- os discriminadores de criação e de cancelamento;
- as sementes da conta de custódia;
- a posição de cada conta nas instruções.

A `TriggerPolicy` só é registrada quando esse arquivo existe e confere com o hash gravado. Sem o arquivo, o `/signatures` recusa `TRIGGER_*` com `POLICY_NOT_RECORDED`.

As transações gravadas viram os vetores dos testes de ataque, que são escritos antes do código da política.
B. Valores fixos no código
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Exportação] Como gerar em fluxo?

A. `StreamingResponseBody` no controlador, com um gravador próprio (BOM, `;`, vírgula decimal, `\r\n`, escape de BR7.5) que escreve cada linha assim que a M2 entrega o grupo de lançamentos da mesma operação, já ordenados por instante e referência.

A conversão de unidades usa `BigDecimal` com as casas do mint. O limite de 10 por hora é conferido antes de abrir o fluxo, e a auditoria grava só o período e a contagem de linhas.
B. Montar o arquivo na memória
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Flag e modo] Onde fica o flag e quem confere?

A. O flag `memetrade.real.exits.enabled` fica em `RealProperties` (API e worker), desligado por padrão. Ele é conferido em dois lugares:
- **Na troca de modo da U4**, pela interface `ExitsAvailability` (`botwallet.api`) implementada pela U7. Com o flag desligado, a subida para `AUTO_EXITS` é recusada com `EXITS_DISABLED`.
- **Na criação de condicional.**

O web lê o estado do flag na `GET /api/v1/real/release`, que ganha o campo `exitsEnabled`. Com isso, a tela mostra "Em breve" sem variável `NEXT_PUBLIC_*`.
B. Variável no web
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Observabilidade] Como seguir o padrão?

A. `RealExitsMetrics`, com rótulos fechados e contadores começando em zero. Um `RealExitsGaugesJob` de 30 s preenche os medidores de estado, de cancelamento mais antigo e de leitura mais antiga.

As regras ficam em `infra/observability/real-exits-alerts.yml` e os alarmes em `infra/terraform/modules/observability/real-exits.tf` (`Eureka/RealExits`), com o teste `RealExitsAlarmParityTest`.

A linha do `promtool` no `ci.yml` vai pronta para o responsável.
B. Só logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Módulo novo `realexits` com a exportação em `realexits.fiscal`, leitura em fluxo da M2 e regras de arquitetura novas [Q1].
- Sem ciclo: a U4 e a U5 declaram interfaces de extensão (`PositionExitGuard`, `EscrowBalances`, `OpenExitsCheck`) que a U7 implementa [Q2].
- Trava por pessoa da M5 para estado e venda, compare-and-set, ShedLock; a venda espera pela fila em vez de travar [Q3].
- `TriggerPolicy` lê o `trigger-program.json` gerado pela gravação real; sem ele, recusa [Q4].
- Exportação em fluxo com gravador próprio, `BigDecimal`, limite antes do fluxo e auditoria sem conteúdo [Q5].
- Flag em `RealProperties`, conferido na troca de modo e na criação; o web lê `exitsEnabled` na leitura da liberação [Q6].
- Métricas, gauges, alertas e alarmes no padrão da U5, com teste de paridade [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
