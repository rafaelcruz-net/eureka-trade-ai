# Pipeline de CI/CD — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 não muda os workflows. Os jobs do `ci` e do `security` continuam os mesmos, e o spec novo entra no `scripts/verify.sh`, que os jobs `smoke` e `e2e` já rodam [Q4]. Só o responsável do projeto altera o `verify.sh` e os arquivos de `.github/workflows/` (`team.md`, Way of Working); a Code Generation entrega a ele o texto pronto.

## Estágios da CI e portões

| Job do `ci` | O que roda para a U1 | Portão | NFR |
|---|---|---|---|
| `backend` | `mvnw verify` da API: Spotless, testes de unidade e de integração da U1 (inclusive `ModePnlLatencyIT`, `RealBotCreationEligibilityIT`, `ModePnlFailureIT`, `BotModePathIT`, `RealSeedProfileGuardTest`, o teste de integração da ligação das três chaves (abaixo) e as regras ArchUnit `NEVER_SUM_MODES` e de `BotsArchitectureTest`), JaCoCo com piso de 80%; `docker compose config`; `promtool check rules` com o mesmo número de regras | qualquer falha deixa o commit vermelho | NFR1.5, NFR3.8, NFR4.1, NFR7.1 a NFR7.6, NFR8.5 |
| `frontend` | lint, formatação, tipos e Jest do `apps/web` (telas de Bots e de P&L por modo), `coverageThreshold.global.lines = 80` | idem | NFR5.x, NFR6.x, NFR7.3, NFR7.8 |
| `contract` | testes de contrato do OpenAPI e `check:api` do `apps/web` (C1 a C4) | quebra de contrato falha | NFR7.8 |
| `smoke` | `bash scripts/verify.sh` com o spec novo `e2e/paper-real-unico` e a guarda da semente | código de saída 0 | NFR7.7, NFR3.8 |
| `admin` | sem mudança da U1 | idem | — |
| `txsigner` | sem mudança da U1 (a U1 não chama o TxSigner) | idem | NFR3.9 |
| `e2e` (só no push para `main`, depois dos seis jobs) | `bash scripts/verify.sh` de novo | código de saída 0 | NFR7.7 |
| workflow `security` | Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit` | Crítico ou Alto bloqueia | NFR3.7 |
| workflow `load-test` (manual) | sem mudança; o portão de 1.000 ms de C4 é da U2 | — | NFR1.6 |

## Teste de integração da ligação das chaves (job `backend`)

As três chaves têm hífen e são lidas só por `@ConfigurationProperties` (`PnlByModeProperties` e `BotProperties`), nunca por `@Value` (`infrastructure-specification.md`, "Configuração nova"). O teste novo, escrito na Code Generation, roda no `mvnw verify` do job `backend`:

| Caso | Entrada | Portão |
|---|---|---|
| Ligação pelo ambiente | `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT`, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT` e `MEMETRADE_PNL_BYMODE_REALMAXORDERS` com valores dentro da faixa e diferentes dos padrões | os valores ligados são exatamente os do ambiente; senão o job fica vermelho |
| Falha rápida | uma das variáveis fora da faixa | a subida do contexto falha na hora, com mensagem que nomeia a propriedade; se subir, o job fica vermelho |

## Obrigações da Code Generation fora do código Java

| Obrigação | Arquivo | Motivo |
|---|---|---|
| Remover a linha comentada `# MEMETRADE_REAL_SEED_TEST_RELEASE=` (hoje a linha 133) | `.env.example` | A guarda da semente é estrita e falha com qualquer ocorrência, inclusive em comentário; uma chave só de teste não fica no arquivo de exemplo (NFR3.8). |

## Mudanças no `scripts/verify.sh` (pelo responsável)

| Ordem | Mudança | Onde |
|---|---|---|
| 1 | Guarda da semente, antes de subir o ambiente: falha com código diferente de zero se o texto `MEMETRADE_REAL_SEED_TEST_RELEASE` aparecer em **qualquer** linha, inclusive comentada, fora de `infra/docker-compose.real.yml`, ou se um `SPRING_PROFILES_ACTIVE` com `test` aparecer fora de `infra/docker-compose.real.yml` e `infra/docker-compose.test.yml` (por exemplo, em `infra/terraform/`, nos Dockerfiles, em `.github/workflows/` ou em `.env.example`). Mesma regra do `RealSeedProfileGuardTest`. | começo do script, depois das variáveis |
| 2 | Spec `e2e/paper-real-unico` acrescentado à lista do `playwright test`. Hoje a lista ocupa as linhas 161 e 162 do `verify.sh` e o último spec é `e2e/real-v2-exits`, no fim da linha 162; o spec novo entra depois dele. Ele cria um bot PAPER e um bot REAL no validador local, faz uma compra em cada modo e confere os blocos Simulado (US$) e Real (SOL), sem total geral. | linha 162, depois de `e2e/real-v2-exits` |

A composição continua `docker-compose.yml` + `infra/docker-compose.test.yml` + `infra/docker-compose.real.yml`; nenhuma composição nova. O comando de verificação do intent continua `bash scripts/verify-checkpoint.sh`, que roda o `verify.sh` local primeiro e, se a máquina não aguentar, aceita o run verde do `ci` do mesmo commit (`team.md`, Walking Skeleton).

## Artefatos

| Artefato | Mudança |
|---|---|
| Imagem `api` (API e worker) | A U1 entra na imagem de sempre, marcada com o SHA do commit, publicada no ECR `${name}/api` com etiqueta imutável |
| Imagem `web` | idem, no ECR `${name}/web` |
| Imagens `txsigner` e `admin` | sem mudança de código da U1 |
| Terraform | três variáveis em `api_environment` de `infra/terraform/main.tf` (`infrastructure-specification.md`); aplicado pelo fundador, fora do `deploy.yml` |

## Implantação e promoção

O `deploy.yml` (manual, `workflow_dispatch`, GitHub Environments `devnet` e `mainnet`, OIDC) continua uma pendência do responsável e ainda não existe no repositório. Quando existir, a U1 segue o fluxo de `team.md`:

1. Commit de `main` com `ci` e `security` verdes (`project.md`, Mandated).
2. O fundador aplica o Terraform com as três variáveis. A ordem em relação à imagem não importa, porque os valores são iguais aos padrões.
3. **Devnet:** o fundador pode implantar a U1 sozinha, só para os próprios testes; a devnet não tem usuários (NFR8.3).
4. **Mainnet:** só num SHA que já contém a U2 com o Build and Test aprovado (NFR8.4, BR10.3), com a aprovação do fundador no Environment `mainnet`. A U1 nunca vai para a mainnet, nem para uma devnet com convidados, antes disso [Q1].
5. Estratégia: atualização rolling no ECS (tarefa nova saudável antes de a antiga sair), circuit breaker com volta automática, smoke e janela de observação de 10 minutos. Os critérios de aborto são os de `deployment-strategy.md` do intent `261113-trading-real-bot`; a U1 não muda nenhum.

| Ambiente | Pode receber a U1 sem a U2? | Quem aprova |
|---|---|---|
| Local e CI | sim (ponto de controle do esqueleto) | portões da CI |
| Devnet (sem usuários) | sim, só para testes do fundador | fundador |
| Devnet com convidados | não | — |
| Mainnet | não; só com a U2 aprovada no mesmo SHA | fundador, revisor obrigatório do Environment `mainnet` |

## Rollback

| Passo | Ação |
|---|---|
| 1 | Volta a imagem anterior (`api-worker` e `web`) pelo procedimento do `rollback-runbook.md` do intent `261113-trading-real-bot`. |
| 2 | Nenhuma migração a desfazer: a U1 tem 0 migrações (NFR1.13). |
| 3 | As três variáveis podem ficar na definição de tarefa: a imagem anterior não conhece essas propriedades e as ignora. |
| 4 | O REAL continua desligado (`MEMETRADE_REAL_ENABLED = "false"`); o kill switch é o primeiro passo só se o REAL estiver ligado no ambiente. O saque continua disponível durante todo o rollback (`project.md`, Mandated). |
| 5 | A implantação de volta só termina quando o smoke passa. |

Os bots REAL criados pela U1 continuam na tabela `bot`, que já existe; a imagem anterior já lê bots REAL do intent `261113-trading-real-bot`.

## Segredos na CI/CD

| Item | Decisão |
|---|---|
| Segredos novos | Nenhum (Q5). |
| Segredos efêmeros do `verify.sh` | Os de hoje: sem `.env`, o script gera segredos por execução. |
| Chave da semente | Só em `infra/docker-compose.real.yml`; nunca no Terraform, nas imagens nem nos workflows (NFR3.8). |
| Credencial AWS | OIDC no `deploy.yml`, quando existir; nenhuma chave longa; `id-token: write` só nesse workflow. |

## Sources

- `infrastructure-design-questions.md`: Q1, Q4, Q5 e o resumo confirmado [Q1] [Q4] [Q5].
- `construction/u1-mode-skeleton/nfr-design/reliability-design.md` (Verificação, NFR7.x), `security-design.md` (guarda da semente, NFR3.8), `performance-design.md` (NFR1.5, NFR1.6), `scalability-design.md` (NFR8.3, NFR8.4).
- `construction/u1-mode-skeleton/nfr-requirements/`: NFR7.7, NFR7.8, NFR8.4.
- `construction/u1-mode-skeleton/functional-design/functional-spec.md` e `inception/contract-design/contract-summary.md`.
- `.github/workflows/ci.yml` (jobs `backend`, `frontend`, `contract`, `smoke`, `admin`, `txsigner`, `e2e`), `scripts/verify.sh` (composições e lista de specs), `aidlc/spaces/default/intents/261121-paper-real-unico/verification-command.txt`.
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/rollback-runbook.md`, `deployment-strategy.md` e `cd-config.md`.
- `aidlc/spaces/default/memory/team.md`: Way of Working, Walking Skeleton e Deployment; `project.md`: Mandated.

## Assumptions & Open Questions

1. [assumption] A guarda da semente no `verify.sh` repete em shell a regra do `RealSeedProfileGuardTest`, para falhar cedo também no `smoke`; o texto exato vem na Code Generation.
2. [assumption] O nome do spec é `e2e/paper-real-unico`, o usado no NFR Design; a Code Generation confirma.
3. O `deploy.yml` continua pendência do responsável; até ele existir, nenhuma implantação na nuvem acontece.
4. Pendência para a Code Generation: alinhar o texto do `performance-design.md` ("nunca lidos do ambiente") e o critério do NFR8.1 ("0 mudanças nas definições de tarefa") a este desenho (ver `infrastructure-specification.md`, Assumptions, item 3).
