# Perguntas de Infrastructure Design — U4 `u4-real-execution`

Conversa: português (Brasil). Base:
- `construction/u4-real-execution/nfr-design/`: componentes, pools e conexões, observabilidade, desvio da Q2;
- `construction/u1-bot-wallet/infrastructure-design/` e `construction/u3-real-risk/infrastructure-design/`;
- `infra/docker-compose.real.yml` e `scripts/verify.sh` (este só o responsável do projeto altera);
- `team.md`: Deployment, Determinismo (nenhum teste usa rede real) e o rollout do REAL.

## Q1. [Nuvem] O que muda na nuvem?

A. Nenhum serviço novo:
- **Rede:** o worker já sai por HTTPS na subrede com NAT (U2) e passa a falar também com a Jupiter. A lista de domínios fica na aplicação.
- **Configuração:**
  - o `max_connections` do PostgreSQL vai a 150 (grupo de parâmetros do RDS e Compose local);
  - o worker recebe `SCHEDULER_POOL_SIZE=19` e `DB_POOL_MAX_SIZE` entre 27 e 40.
- **Segredos:** a Jupiter não exige chave no plano usado, então não há segredo novo.
B. Um serviço dedicado à execução
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Local] Como provar o swap sem rede real?

A. As contas da Jupiter e de um pool de teste são **gravadas uma vez** (com `solana account --output json`) e versionadas em `infra/solana-fixtures/jupiter/`. O validador local do Compose do REAL sobe com `--account` para cada arquivo, sem clonar da mainnet na hora. Um contêiner WireMock com as respostas gravadas da cotação e das instruções de swap sobe só nesse Compose.

O spec novo de ponta a ponta (`e2e/real-swap/`) passa a ser exercitado pelo `verify.sh`:
- ativa o bot REAL em Automática total;
- compra;
- confere a ordem confirmada e os lançamentos;
- vende tudo e saca.

O texto da mudança no `verify.sh` vai pronto para o responsável do projeto.
B. Clonar as contas da mainnet a cada execução
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Alertas] Onde ficam os alertas da U4?

A. Como na U3:
- regras do Prometheus em `infra/observability/real-execution-alerts.yml`, conferidas pelo `promtool`;
- alarmes em `infra/terraform/modules/observability/real-execution.tf`, no namespace `Eureka/RealExecution` e no tópico SNS único, com teste de paridade;
- o alarme de p95 fica adiado.

O caminho das métricas até o CloudWatch continua como pendência única de U1 a U4, a decidir antes da mainnet.
B. Só regras do Prometheus
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [CI] O que muda na CI?

A. O `ci.yml` é do responsável do projeto. Vai pronto, no code-summary, o texto de três mudanças:
- o job `txsigner` com `mvnw verify` e o piso de 80%, que já estava pendente desde a U1;
- o arquivo de alertas novo na etapa do `promtool`;
- o spec `e2e/real-swap/` no `verify.sh`.

Os testes da API rodam no job `backend` que já existe.
B. Mudar o `ci.yml` nesta Unit
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Implantação e rollback] Em que ordem?

A. A ordem:
1. migração (só expansão);
2. TxSigner, porque o R1 muda antes da API (regra do contrato);
3. worker;
4. API.

Bots REAL só podem ser criados depois da liberação do fundador (U5).

Rollback:
1. desligar o REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) ou acionar o kill switch REAL;
2. esperar as ordens enviadas e ambíguas terminarem, acompanhando no painel;
3. voltar as imagens.

As tabelas ficam. O saque continua disponível o tempo todo.
B. Rollback sem esperar as ordens enviadas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Nenhum serviço novo na nuvem: o worker passa a falar com a Jupiter, o PostgreSQL vai a 150 conexões, os pools do worker crescem e não há segredo novo [Q1].
- Contas da Jupiter gravadas e carregadas no validador local, Jupiter simulada no WireMock, spec de swap de ponta a ponta no `verify.sh` [Q2].
- Alertas no padrão da U3; p95 adiado; CloudWatch pendente [Q3].
- CI e `verify.sh` com texto pronto para você aplicar: job do TxSigner, `promtool` e o spec do swap [Q4].
- Ordem: migração, TxSigner, worker e API; o rollback desliga o REAL e espera as ordens enviadas; o saque fica sempre livre [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
