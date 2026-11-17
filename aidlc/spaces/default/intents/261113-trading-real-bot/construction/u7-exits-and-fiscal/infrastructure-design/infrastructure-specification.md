# Especificação de infraestrutura — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes:
- `construction/u7-exits-and-fiscal/nfr-design/`: componentes, interfaces de extensão, migração 0022, pools e `trigger-program.json`;
- `infrastructure-design-questions.md` (Q1 a Q4);
- o padrão da U5 e da U6;
- os arquivos atuais: `docker-compose.yml`, `infra/docker-compose.real.yml`, `scripts/verify.sh`, `infra/solana-fixtures/record.sh` e `infra/terraform`;
- a revisão da iteração 1.

## Compute, rede e segredos [Q1]

| Item | Mudança |
|---|---|
| Worker | scheduler de 25 para 28 (`ConditionalReadJob`, `ConditionalCancelJob`, `RealExitsGaugesJob`); pool de conexões de 33 para 36. Mudam junto: `docker-compose.yml`, `application.yml`, as constantes `MIN_WORKER_*` e a Javadoc do `RuntimeSettingsGuard`, e o `ComposeFilesTest` com o comentário dele. No Compose local, o total de conexões passa de 62 para 65 de 100, ou de 72 para 75 com o TxSigner. |
| API | mínimo do scheduler de 22 para 23: o `RealExitsPurgeJob` roda na API, não no worker |
| Segredo | `jupiter-api-key` no Secrets Manager, lido pela API e pelo worker e vazio até a gravação. No local e nos testes ele não existe. Variável: `MEMETRADE_JUPITER_API_KEY` |
| Rede | a saída para `api.jup.ag` já existe desde a U4; nada novo |
| TxSigner | sem o `trigger-program.json` (antes da gravação), sobe normalmente e recusa `TRIGGER_*` com `POLICY_NOT_RECORDED`, coberto por teste no job `txsigner` |
| Worker na nuvem | a tarefa do worker, pendente desde a U2, recebe as variáveis e o segredo quando for criada |

## Banco: migração 0022, só de expansão

- **Tabela nova `real_conditional_order`**, com:
  - o índice único parcial;
  - os índices (carteira, estado) e (pessoa, criação);
  - a matriz de privilégios no padrão da U4;
  - o gatilho de colunas mutáveis;
  - o gatilho de idade de 5 anos para o expurgo.
- **`real_decision_request`** ganha `not_before` (timestamptz, `DEFAULT now()`) e `attempts` (int, `DEFAULT 0`), sem quebrar as imagens antigas.
- **Rollback por changeset:**
  - a tabela nova só é apagada se estiver vazia; com dados, o rollback para com uma mensagem, e só as imagens voltam;
  - as colunas novas de `real_decision_request` ficam.
- **Teste de privilégios e gatilhos:** confere a matriz, o gatilho de idade e o índice único.

## Caminho simulado (ajuste da revisão)

O adaptador simulado existe para provar o domínio, a API, as telas e o cancelamento sem a Jupiter real. Ele não monta transação, não chama o TxSigner e não mexe na rede nem no ledger.

**Criação no modo simulado:**
- a "ordem" é só um registro do adaptador;
- a autorização da pessoa é a assinatura de uma mensagem de consentimento da condicional (`signMessage` da carteira principal), a mesma forma prevista em BR1.2 para o caso de a Jupiter não aceitar cossinatura;
- a condicional passa de `PENDING_SIGNATURE` para `OPEN` com a assinatura conferida.

**Cancelamento no modo simulado:** o adaptador marca a ordem cancelada, e a condicional vai de `CANCELLING` a `CANCELLED` na próxima execução do trabalho.

**Ledger e reconciliação:**
- o modo simulado nunca preenche, e o ledger não muda;
- o token continua na carteira do bot, e o `EscrowBalances` devolve zero para as ordens simuladas, então a reconciliação continua limpa;
- o preenchimento (BR5) é provado por testes de integração com transações e respostas do RPC gravadas, não no e2e.

**Proteções para que o caminho simulado nunca rode fora do teste:**
- o bean `SimulatedTriggerAdapter` só existe com `@Profile("test")`;
- o `RealProperties` recusa a subida com `memetrade.real.exits.adapter=simulated` fora da rede `LOCALNET`;
- um teste confere que o contexto do perfil de produção não tem o bean simulado;
- uma regra ArchUnit impede que código de produção referencie o adaptador simulado;
- não existe rota de teste nova: o e2e só usa as rotas R5 normais.

## Local e ponta a ponta [Q2]

**Perfil de teste local** (`infra/docker-compose.real.yml`, serviços API e worker):
- `MEMETRADE_REAL_EXITS_ENABLED=true`;
- `MEMETRADE_REAL_EXITS_ADAPTER=simulated`.

**Spec `apps/web/e2e/real-exports/`**, depois do `real-ui`, só lê:
- exporta o ano corrente;
- confere o BOM, o `;`, a vírgula decimal e o cabeçalho;
- confere a linha `COMPRA` do `real-swap` com os valores do ledger;
- confere a soma de BR7.6;
- confere o `422` para período maior que 1 ano.

**Spec `apps/web/e2e/real-exits/`**, sempre o último da lista:
1. Usa a conta de fumaça e a posição que ainda resta do `real-swap`.
2. Muda o modo para Automática nas saídas.
3. Cria um OCO pela tela, com o consentimento assinado pela carteira de teste, e confere `OPEN` na aba "Condicionais".
4. Cancela pelo `DELETE` e confere `CANCELLED`.
5. Cria outra condicional e liga o kill switch REAL pelo administrador, dentro de `try/finally` que o desliga. Confere `CANCELLED`.
6. Na saída, volta o modo para AUTO_FULL com consentimento e deixa o kill switch desligado.

Como esse spec é o último do `verify.sh`, nenhum spec depende do estado que ele deixa. O spec e o `verify.sh` dizem isso em comentário.

## Gravação real (BR0), sem chave e sem enviar nada (ajuste da revisão)

O `record.sh` continua sem assinar, sem enviar e sem chave. O modo novo `--trigger`:
- pede à Trigger API as transações de criação e de cancelamento sem assinatura, para uma carteira pública escolhida pelo responsável, que tenha saldo do mint (só leitura);
- grava essas transações e as respostas, o id do programa, os discriminadores, as contas e a derivação da conta de custódia;
- clona o programa Trigger e as contas para o validador local;
- não assina e não envia nada.

Com isso são gerados o `trigger-program.json`, o `.sha256` e os vetores dos testes de ataque. Se a Trigger API recusar montar a transação para uma carteira sem chave, a gravação para, e a decisão volta para o responsável antes de qualquer gasto, como manda o `project.md`: fundos reais só com aprovação explícita.

## CI [Q3]

Textos prontos para o responsável:
- **`.github/workflows/ci.yml`**, passo `Alert rules (promtool)`: `real-alerts marketdata-alerts real-risk-alerts real-execution-alerts real-ops-alerts real-exits-alerts`.
- **`scripts/verify.sh`**, lista do Playwright: `... e2e/real-ops e2e/real-ui e2e/real-exports e2e/real-exits`, com o `real-exits` por último.

Nenhum segredo da Jupiter entra na CI.

## Implantação e rollback [Q4]

**Ordem:**
1. migração 0022;
2. worker e API;
3. TxSigner, que não muda sem a gravação;
4. web e admin.

Depois de cada etapa roda o smoke. O flag continua desligado fora do perfil de teste local.

**Rollback (ajuste da revisão).** Uma condicional `OPEN` ou `CANCELLING` tem o token na custódia da Jupiter, e as imagens antigas não sabem ler, cancelar nem reconciliar essa ordem. O rollback segue estes passos:
1. Desligar o flag `memetrade.real.exits.enabled`.
2. Ligar o kill switch REAL, que cancela todas as condicionais não terminais (BR6.1).
3. Esperar `real_conditionals{state=~"OPEN|CANCELLING|PENDING_SIGNATURE"}` chegar a zero; o limite é de 5 min com a rede saudável.
4. Só então voltar as imagens, na ordem inversa: web e admin, TxSigner, worker e API.
5. A migração 0022 segue a regra da tabela vazia; as condicionais finais ficam.
6. Com as imagens antigas no ar e o smoke verde, desligar o kill switch REAL.

O `ConditionalCancelJob` cancela mesmo com o flag de saídas desligado, porque o flag só bloqueia a criação (BR8.1). Um teste de integração cobre esse caso.

**Lacuna aceita:** até a gravação real, o preenchimento (BR5) só tem prova em testes de integração com transações e RPC gravados, sem teste de ponta a ponta. Isso também fica registrado no code-summary.

Com alguma condicional não terminal depois de 15 min, o rollback para, e o alerta de cancelamento parado já está ativo. O saque continua disponível o tempo todo. Os passos ficam no runbook `docs/runbooks/real-exits.md`.
