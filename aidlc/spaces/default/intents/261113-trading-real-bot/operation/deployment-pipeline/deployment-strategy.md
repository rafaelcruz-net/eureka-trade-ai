# Estratégia de implantação — intent `261113-trading-real-bot`

Data: 2026-11-20. Conversa: português (Brasil). Decisões: `deployment-pipeline-questions.md` (Q1 a Q5, opção A). O workflow e as configurações estão em `cd-config.md`; o passo a passo do rollback, em `rollback-runbook.md`.

> **Correção da revisão de segurança (2026-11-20, `environment-provisioning/security-posture-review.md`, SP-01).** A ordem não muda: TxSigner primeiro, depois `api-worker`, web e admin. Muda quem executa: a migração do TxSigner, a revisão nova do TxSigner e a conferência de prontidão (passos 3, 5 e 6 da tabela de ordem) são feitas **pelo fundador, com MFA, antes do `deploy.yml`** (`cd-config.md` 4.6), porque o papel de implantação não pode mais passar o papel do TxSigner nem atualizar o serviço dele. O workflow só confere, por leitura, que o TxSigner em serviço roda a imagem do SHA e está saudável. No rollback, o TxSigner volta pelo fundador depois do workflow (`rollback-runbook.md`, passo 4, A2).

## 1. Estratégia: rolling no ECS

**Escolha [Q1]:** atualização rolling do ECS em todos os serviços, com `minimumHealthyPercent=100` e `maximumPercent=200`. A tarefa nova sobe e passa no health check antes de a antiga sair. O deployment circuit breaker do ECS fica ligado, com rollback automático para a revisão que estava rodando.

**Por que rolling:**
- **Cabe no piloto.** Uma tarefa por serviço, numa zona, com custo de hospedagem de cerca de US$ 154 por mês (`infrastructure-specification.md` da U1). O rolling 100/200 dobra a tarefa só durante a troca, por poucos minutos.
- **Respeita a instância única de assinatura.** O TxSigner nunca fica sem uma instância saudável, porque a antiga só sai depois de a nova passar no health check (seção 6).
- **O código já aguenta duas versões lado a lado.** As migrações são só de expansão, as ordens REAL e os saques são idempotentes, e os trabalhos agendados usam ShedLock (seção 4).

**Alternativas recusadas:**

| Alternativa | Por que não |
|---|---|
| Blue/green com CodeDeploy e dois target groups [Q1 B] | o ambiente inteiro em dobro durante a troca e um segundo conjunto de peças (CodeDeploy, AppSpec, dois target groups, ganchos). Ganha uma volta instantânea pelo load balancer, mas a volta do REAL começa pelo kill switch de qualquer forma (`team.md`), e o TxSigner, que não fica atrás do load balancer, não ganharia nada |
| Canary por porcentagem de tráfego [Q1 C] | com uma tarefa por serviço e poucos usuários no piloto, uma fatia de 5% ou 10% do tráfego não produz sinal medível em minutos. Também exigiria dividir o tráfego no load balancer e duplicar o worker, que não recebe tráfego HTTP |

## 2. Ordem e prontidão

### 2.1 Implantação (`mode=deploy`)

| # | Passo | Pronto quando | Se falhar |
|---|---|---|---|
| 1 | Guarda de configuração (rede, flags, RPC, fundador) | todas as regras aceitas | o job para antes de qualquer mudança |
| 2 | Imagens com a etiqueta do SHA no ECR | digest conhecido para cada imagem | o job para; nada mudou na nuvem |
| 3 | `txsigner-migrate` (tarefa avulsa, subrede A) | código de saída 0 | o job para; o Liquibase aplica cada changeset numa transação; o que já entrou é só expansão e é compatível com a imagem em serviço |
| 4 | `db-migrate` (tarefa avulsa, subrede B) | código de saída 0 | idem |
| 5 | Serviço `txsigner` | revisão nova `COMPLETED`, 1/1 em execução | circuit breaker devolve a revisão anterior; o job para |
| 6 | Prontidão do TxSigner (probe avulso em `:8444/actuator/health/readiness`: banco e `ListKeys`) | resposta 200 em até 30 tentativas de 10 s | o job para; o TxSigner novo fica em serviço, e o fundador decide pelo `rollback-runbook.md` |
| 7 | Serviço `api-worker` | revisão nova `COMPLETED`; alvos do load balancer saudáveis pela readiness (`readinessState`, `db`, `migrations`) | circuit breaker devolve a revisão anterior; o job para |
| 8 | Serviços `web` e `admin` (se `WEB_ADMIN_ON_ECS=true`) | revisões novas `COMPLETED` | circuit breaker; o job para |
| 9 | Smoke | seção 5 | o job fica vermelho; `rollback-runbook.md` |
| 10 | Janela de observação de 10 min | seção 5 | o job fica vermelho; `rollback-runbook.md` |

A ordem segue a resposta confirmada da Q1 (TxSigner, depois worker e API) e as especificações da U1, da U4 (o contrato R1 muda no TxSigner antes da API) e da U6 (web e admin depois da API).

**Diferença com a U7, resolvida aqui:** a U7 tinha posto o TxSigner depois do worker e da API, porque o TxSigner da U7 não muda sem a gravação real. A ordem única deste estágio põe o TxSigner primeiro, como na Q1. Isso continua seguro para a U7: o TxSigner sem `trigger-program.json` recusa `TRIGGER_*` com `POLICY_NOT_RECORDED`, e as saídas condicionais estão desligadas.

**API e worker juntos:** no piloto, a API e o worker são dois contêineres da mesma tarefa (`infrastructure-specification.md` da U1), então trocam juntos. As especificações da U2, U3 e U5 pediam o worker antes da API. Com a tarefa única, isso não é possível, e não é necessário: as mudanças dessas Units são compatíveis nas duas direções (migrações só de expansão, contrato só com acréscimos). Quando o worker ganhar uma tarefa própria (Environment Provisioning), a ordem passa a ser `txsigner` → `worker` → `api`.

### 2.2 Rollback (`mode=rollback`)

Sem migração. Ordem inversa: `admin` e `web` → `api-worker` → `txsigner`, conferindo de novo a prontidão do TxSigner no fim. O detalhe está no `rollback-runbook.md`.

## 3. Migrações: expand-contract

- **Só expansão na mesma implantação.** Uma migração acrescenta tabela, coluna anulável ou com padrão, índice, gatilho ou CHECK mais largo. Ela nunca remove nem renomeia coluna junto com a mudança da aplicação (`team.md`, Rollback). A imagem anterior continua funcionando sobre o esquema novo. É isso que permite o rollback só das imagens.
- **Contração numa implantação posterior,** depois que nenhuma imagem em serviço nem nenhum alvo de rollback usa mais o que vai ser removido. Como a retenção do ECR guarda as 30 últimas imagens, a contração só entra quando o alvo de rollback já é uma imagem que não lê a coluna.
- **Ordem:** `txsigner-migrate` antes de `db-migrate`, e as duas antes de qualquer serviço (`cicd-pipeline.md` da U1).
- **Credenciais:** o login dono de cada esquema existe só na família de migração correspondente. A API, o worker e o TxSigner nunca o recebem (NFR6.3 do intent anterior, mantido).
- **Nunca reverter migração em produção.** Os blocos de rollback dos changesets (por exemplo, 0021 e 0022) servem ao desenvolvimento. Em produção o esquema fica, e voltam só as imagens. Os casos em que nem o desenvolvimento pode voltar estão nos runbooks: a 0021 com saque redigido (`docs/runbooks/real-operations.md`, seção 7) e a 0022 com condicional gravada (`docs/runbooks/real-exits.md`).
- **Restauração do banco por PITR** não é rollback [Q4 B recusada]. Ela serve a desastre de dados, pelo `docs/runbooks/txsigner-recovery.md`, porque perderia ledger, saques e auditoria posteriores ao ponto restaurado.

## 4. Duas versões lado a lado durante a troca

| Serviço | O que roda junto | Por que é seguro |
|---|---|---|
| `api-worker` | por alguns minutos, duas tarefas: dois contêineres `api` e dois `worker` | a API não guarda estado em memória (o token de acesso fica no navegador). Os trabalhos agendados do worker usam ShedLock. Os saques usam lease de 60 s (`withdrawal-lease`). As ordens REAL são deduplicadas pela `Idempotency-Key` e pela fila de decisões, testadas no B&T ("uma única transação por ordem REAL") |
| `txsigner` | por alguns minutos, duas instâncias ligadas ao mesmo RDS do TxSigner | cada assinatura é registrada pelo `signingRequestId`; o mesmo pedido nunca gera duas assinaturas diferentes. A política de conteúdo é a mesma nas duas versões, ou mais estrita na nova |
| `web`, `admin` | duas versões do Next.js | o contrato da API só ganha campos; o web antigo sobre a API nova funciona (U6) |

## 5. Critérios de tráfego e de aborto

A regra da fase Operation pede critérios de troca de tráfego e condições de aborto. Com rolling, o tráfego vai para a tarefa nova quando ela passa no health check, e a antiga sai em seguida. Os critérios abaixo decidem se a implantação segue ou para.

| Critério | Medido por | Limite | O que acontece |
|---|---|---|---|
| **Saúde do contêiner** | health check do ECS (liveness) | 6 falhas seguidas, depois de 60 s de início | a tarefa é trocada; repetido, o circuit breaker devolve a revisão anterior **automaticamente** e o job para |
| **Saúde no load balancer** | target group da API na readiness (`:8081`) | 3 falhas seguidas | o ECS não conta a tarefa como saudável; o circuit breaker age como acima |
| **Prontidão do TxSigner** | probe avulso na readiness (`db`, `kms`) | sem 200 em 5 min | o job para antes de trocar a API e o worker |
| **Smoke** | job `deploy` | qualquer falha: digest diferente em alguma tarefa; algum alvo não saudável; `GET /api/v1/legal/current` sem versão ou com `publishable` falso; `/api/health` ou `/privacidade` do web e do admin com erro; TxSigner sem prontidão depois da troca da API | o job fica vermelho; o fundador segue o `rollback-runbook.md` |
| **Taxa de erro** | `HTTPCode_Target_5XX_Count` / `RequestCount` do target group, 10 min depois da última troca | acima de 1%, com pelo menos 5 erros | o job fica vermelho; o fundador decide pelo `rollback-runbook.md` |
| **Alarmes do REAL** | `cloudwatch describe-alarms` com o prefixo `${name}-` (KMS, mercado, risco, execução, operações, saídas) | qualquer alarme em `ALARM` no fim da janela | o job fica vermelho; o fundador decide |

**Base dos limites.** O SLO de disponibilidade da U1 é 99,5% em 30 dias (U1 NFR4.1, medido pelo `performance-validation`). Uma taxa de 1% de erros por 10 minutos consome menos de 0,05% do orçamento mensal, ou seja, menos de 10% dele. Assim a implantação para antes de pôr o SLO em risco. O mínimo de 5 erros evita que um único erro num minuto de pouco tráfego derrube uma implantação. Com menos de 5 erros, a decisão fica com o smoke e os alarmes. Os limites são uma proposta deste estágio e podem ser ajustados pelo `observability-setup`, sem nunca ficarem mais frouxos que o SLO.

**Por que o workflow não volta as imagens sozinho depois do smoke.** O `team.md` manda o rollback do REAL começar desligando o REAL ou acionando o kill switch. O kill switch é uma rota administrativa que exige o login do fundador com a carteira dele. O workflow não tem e não deve ter essa credencial. Por isso, depois que uma revisão nova ficou saudável, qualquer volta é decidida e iniciada pelo fundador. O circuit breaker é a exceção: ele só age enquanto a revisão nova nunca ficou saudável, e nesse caso a antiga continuou atendendo.

## 6. Risco do TxSigner único durante a troca

| Situação | O que acontece | Efeito no usuário | Tratamento |
|---|---|---|---|
| Troca normal | a nova sobe; quando passa na liveness, o Service Connect passa a mandar pedidos para ela também; a antiga sai | nenhum | — |
| A nova passa na liveness antes de ficar pronta (KMS ainda não respondeu ao `ListKeys`) | um pedido que cai nela pode receber 503 | o cliente do TxSigner na API e no worker tem limite de 2 s; o pedido falha e é repetido | saques: o trabalho repete com `attempts` e `nextAttemptAt`; ordens REAL: a fila repete com a mesma chave de idempotência, sem segunda transação. O deploy só segue para a API depois da prontidão confirmada |
| A nova nunca fica saudável | o circuit breaker devolve a revisão anterior; a antiga nunca saiu | nenhum | o job para; investigar pelos logs |
| A antiga cai durante a troca (falha da zona ou do Fargate) | não há segunda zona no piloto | assinatura indisponível até a nova ficar pronta (minutos) | risco aceito do piloto de uma zona (`infrastructure-specification.md` da U1); os pedidos esperam e são repetidos |
| KMS com limitação de chamadas ou fora do ar | a readiness cai; a liveness não, então a tarefa não reinicia em laço | assinatura indisponível | alarme `${name}-tx-signer-kms-errors`; o deploy não segue sem prontidão |

**Saque sempre disponível (`project.md` Mandated).** Nenhum passo de implantação ou rollback desliga o saque para a carteira principal registrada: nem o kill switch, nem o REAL desligado, nem a falta de liberação, nem a falta do novo aceite dos termos. Durante a troca, um saque pode atrasar por alguns segundos, mas nunca é recusado por causa da implantação. Ele é repetido até ser assinado e enviado uma única vez. Se o TxSigner ficar indisponível, os saques ficam na fila e saem quando ele volta. O alerta de saque parado (U1) avisa se a espera passar do limite.

## 7. Portões específicos do REAL

1. **Kill switch primeiro.** Todo rollback que envolve o REAL começa pelo kill switch REAL ligado ou pelas flags `memetrade.real.enabled` e `txsigner.real.enabled` desligadas (`team.md`). O `deploy.yml` recusa `mode=rollback` sem `real_off_confirmed`.
2. **REAL desligado por padrão.** As flags valem `false` no código. Ligar o REAL num ambiente é uma implantação auditada separada (`cd-config.md`, seção 7), depois da aprovação do fundador. Nunca entra junto com uma mudança de código.
3. **Saídas condicionais desligadas** em todo ambiente da nuvem até a gravação real da Trigger e a decisão do fundador. A guarda de configuração impõe isso (condição 1 da passagem condicional).
4. **Liberação por pessoa com 24 h de quarentena** e portões (`CI_RED` lê a CI do `BUILD_SHA` da imagem). Por isso toda imagem é construída com `--build-arg BUILD_SHA=<sha>`.
5. **Mainnet só pelo mesmo SHA já verde na devnet,** com a aprovação do fundador e os pré-requisitos da matriz de promoção (`cd-config.md`, seção 6).
6. **Implantação com o REAL ligado.** O rolling foi desenhado para não parar o REAL. Numa versão que mexe na execução REAL (como as da U4 e da U7), o fundador pode ligar o kill switch antes, por precaução, e desligar depois do smoke e da janela de observação. Isso fica a critério dele em cada implantação e é registrado no resumo do run.

## 8. Quando implantar

O fundador é o único on-call. Uma implantação na mainnet só começa quando ele pode acompanhar a janela de observação e os 30 minutos seguintes. Fora disso vale só a devnet. Não há congelamento formal no piloto.

## Sources

- `operation/deployment-pipeline/deployment-pipeline-questions.md` (Q1, Q2, Q4, Q5)
- `construction/ci-pipeline/ci-config.md` e `construction/ci-pipeline/quality-gates.md`
- `infrastructure-specification.md` e `cicd-pipeline.md` das Units U1 a U7 (`construction/u*/infrastructure-design/`)
- `verification/phase-check-construction.md` (condições 1 a 3)
- `apps/api/src/main/resources/application.yml` (flags, health, `withdrawal-lease`, cliente do TxSigner com 2 s) e `apps/txsigner/src/main/resources/application.yml` (readiness `db` e `kms`)
- `docs/runbooks/real-operations.md`, `real-execution.md`, `real-exits.md`, `txsigner-recovery.md`
- `team.md` (Deployment, Testing Posture), `project.md` (Mandated, Forbidden)
- `.claude/knowledge/aidlc-pipeline-deploy-agent/deployment-strategies.md`
