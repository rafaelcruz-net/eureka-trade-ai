# Perguntas do Infrastructure Design — U1 `u1-mode-skeleton`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `construction/u1-mode-skeleton/nfr-design/`: tempos limite, pool `pnl-mode-block`, contadores, teto de leitura e guarda da semente;
- `construction/u1-mode-skeleton/nfr-requirements/`, com o NFR8.4;
- `team.md`, seção Deployment: Terraform em `infra/terraform/`, contas `devnet` e `mainnet`, US$ 350 por conta, `deploy.yml` manual e ECS em rolling;
- o `ci.yml`, com os jobs `backend`, `frontend`, `admin`, `contract`, `smoke`, `txsigner` e `e2e`.

## Q1. Implantação

Como a U1 é implantada?

A. Sem infraestrutura nova. A U1 entra nas imagens que já existem, a API e o worker do mesmo artefato e o `apps/web`, e no ECS em rolling que está desenhado.

A U1 nunca vai para um ambiente com usuários antes da U2. Isso vale para a mainnet e também para uma devnet com convidados. O fundador pode implantar a U1 na devnet para os próprios testes. Nenhum ambiente na nuvem está provisionado hoje. (recomendado)

B. Implantar a U1 sozinha na mainnet
X. Other (please specify)

[Answer]: A

## Q2. Configuração nova

Que configuração nova a U1 traz?

A. Três chaves novas, só como variáveis das definições de tarefa, versionadas no Terraform:

| Chave | Valor padrão |
|---|---|
| `memetrade.pnl.by-mode.block-timeout` | 800 ms |
| `memetrade.bots.real-eligibility-timeout` | 500 ms |
| `memetrade.pnl.by-mode.real-max-orders` | 50.000 |

Elas também têm um valor padrão no `application.yml`. O pool `pnl-mode-block` usa 8 threads e fila de 16, dentro da tarefa da API que já existe, sem CPU ou memória a mais.

A chave da semente, `memetrade.real.seed-test-release`, nunca entra no Terraform nem nas imagens. (recomendado)

B. Chaves no banco
X. Other (please specify)

[Answer]: A

## Q3. Monitoramento

O que muda no monitoramento?

A. Os dois contadores novos (`bots.real.create.refusals{reason}` e `pnl.mode_block.unavailable{market}`) saem no endpoint de métricas que já existe. Na nuvem, o CloudWatch Agent lê esse endpoint a cada 120 s.

Nenhum alarme, painel ou regra do Prometheus novo, então `infra/observability/` não muda. Os logs saem em JSON com o `correlationId`. (recomendado)

B. Alarme para as recusas REAL
X. Other (please specify)

[Answer]: A

## Q4. CI/CD

O que muda na CI e na implantação?

A. Os jobs da CI não mudam: o spec novo entra no `scripts/verify.sh`, que os jobs `smoke` e `e2e` já rodam.

O spec novo e o teste de guarda da semente são acrescentados pelo responsável, com o texto pronto que eu entrego na Code Generation, porque só ele altera o `verify.sh` e os workflows.

O `deploy.yml` continua uma pendência do responsável. O rollback é o mesmo: volta a imagem, sem migração. (recomendado)

B. Job novo na CI
X. Other (please specify)

[Answer]: A

## Q5. Segredos e escala

O que muda em segredos e em escala?

A. Nada muda:
- nenhum segredo novo;
- nenhum serviço AWS novo, dentro do orçamento de US$ 350 por conta;
- a mesma regra de escala das tarefas da API e do worker.

(recomendado)

B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Implantação:** sem infraestrutura nova. A U1 entra nas imagens atuais e no ECS em rolling. Nunca vai para um ambiente com usuários antes da U2; o fundador pode usá-la na devnet [Q1].
- **Configuração:** três chaves novas, versionadas no Terraform e com valor padrão no `application.yml`. O pool roda na tarefa da API atual. A chave da semente nunca entra no Terraform nem nas imagens [Q2].
- **Monitoramento:** dois contadores no endpoint atual, lidos a cada 120 s. Nenhum alarme ou painel novo [Q3].
- **CI/CD:** os jobs não mudam. O spec novo e o teste de guarda entram no `verify.sh` pelo responsável, com o texto pronto. O rollback é o de sempre [Q4].
- **Segredos e escala:** nada muda [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
