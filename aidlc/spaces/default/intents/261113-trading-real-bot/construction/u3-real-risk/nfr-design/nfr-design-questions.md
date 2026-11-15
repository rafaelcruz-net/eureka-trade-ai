# Perguntas de NFR Design — U3 `u3-real-risk`

Conversa: português (Brasil). Base:
- `construction/u3-real-risk/nfr-requirements/` (U3-NFR2.1 a U3-NFR10.1);
- `construction/u3-real-risk/functional-design/` (BR1.1 a BR7.3, F1 a F6);
- o código atual: `riskcontrol` (`TransactionalOrderGate`, `AuditRejectionEvents`, `RiskEvaluationPurgeJob`), `marketdata.api.MarketDataQuery` (leituras por token), `shared` (`@ProcessRole`, ShedLock).

## Q1. [Processo] Onde cada parte roda?

A. A `evaluate` roda no processo do ciclo do bot (worker). A liquidação pedida pelo usuário, `blockToken` e `unblockToken` rodam na API. Os trabalhos F3, F4, F5 e o expurgo rodam só no worker (`@ProcessRole(WORKER)`), com ShedLock.
B. Tudo na API
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Trava] Qual chave usar na trava do PostgreSQL?

A. A forma de duas chaves: `pg_try_advisory_xact_lock(<constante da U3>, hashtext(botWalletId))`. A constante separa as travas da U3 de outras do sistema. Uma colisão de hash só faz duas carteiras esperarem uma pela outra, nunca libera duas avaliações da mesma carteira juntas.
B. Trava de linha (`SELECT ... FOR UPDATE`) na carteira da BotWallet
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Preços das posições] Como ler os preços de uma vez?

A. A M4 ganha uma leitura em lote, `referencePrices(Set<TokenId>) -> Map<TokenId, MarketValue>`, numa só consulta ao banco, com o mesmo `observedAt` e a mesma regra de `unknown` da leitura por token. A U3 a usa para as posições e para o token da compra.
B. Chamar a leitura por token em laço
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Domínio puro] Como separar a decisão da leitura?

A. A regra fica numa classe de domínio pura, `RealRiskPolicy`, que recebe uma foto imutável das entradas (`RealGateInputs`: estado, fonte, avaliação, bloqueio, preços, posições, saldo, reservas, contagem, foto do dia, fluxos e limites) e devolve a decisão, sem I/O nem relógio próprio. A parte de infraestrutura pega a trava, monta a foto e grava o resultado. Os cenários S1 a S17 viram testes de unidade da política, escritos antes do código; a trava e a repetição são testadas com PostgreSQL real.
B. Regra e leitura juntas no serviço
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Repetição] Como gravar sem duplicar?

A. A avaliação é gravada com `INSERT ... ON CONFLICT (decision_id) DO NOTHING`. Quando a inserção não grava nada, a avaliação já existia: a U3 lê o resultado gravado e o devolve. Isso vale para compra (sob a trava) e venda (sem trava). A reserva tem `decision_id` único pelo mesmo motivo.
B. Conferir antes com SELECT e gravar depois
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Expurgo] Como apagar com segurança?

A. Um trabalho diário no worker apaga em lotes de 1.000 linhas as linhas com mais de 13 meses das quatro tabelas REAL (exceto a lista de bloqueio), usando o pool `risk_retention`. A migração dá ao papel o DELETE nessas tabelas e cria um gatilho que recusa apagar linha mais nova que 13 meses, como o da migração `0009`. A declaração de retenção da U3 entra no registro que já existe.
B. Apagar sem gatilho
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- A barreira roda no worker; a liquidação e a lista de bloqueio na API; os trabalhos só no worker com ShedLock [Q1].
- Trava de duas chaves no PostgreSQL, separada das outras travas do sistema [Q2].
- Leitura de preços em lote na M4, numa só consulta [Q3].
- Regra numa classe de domínio pura, testada pelos cenários antes do código; a infraestrutura só lê e grava [Q4].
- Gravação com `ON CONFLICT` para repetição sem duplicar [Q5].
- Expurgo diário em lotes, com permissão e gatilho de idade no banco [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
