# Plano de resposta a incidentes — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Incident Response. Decisões: `incident-response-questions.md`, Q1 a Q4, todas na opção A, com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado.** Não existe conta AWS. Este plano vale a partir do primeiro `apply` da devnet, junto com o plano do 261113.

Peças deste estágio:
- `runbooks.md`: RB-16 a RB-21 (os seis modos de falha da Q1) e a cobertura dos alarmes novos;
- este plano: o delta sobre o plano do 261113;
- `escalation-matrix.md`: plantão, rotas e contatos, com os alarmes novos e propostos.

## 1. Base: o plano do 261113, por referência

Vale **inteiro** o `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/incident-plan.md`:

| Seção do 261113 | Situação aqui |
|---|---|
| 1. Severidades P1, P2, P3 e as regras de classificação | sem mudança; este intent soma um critério P1 e o mapeamento da seção 2 abaixo |
| 2. Ciclo de vida detectar → triar → mitigar → comunicar → recuperar → revisar | sem mudança; o fundador é comandante, investigador e comunicador ao mesmo tempo |
| 3. Remediação automática | sem mudança; nada novo automático (seção 3 abaixo) [Q3] |
| 4. Comunicação, regras e modelos M0 a M4 | sem mudança; este intent soma o modelo M5 (seção 4 abaixo) [Q4] |
| 5. Revisão pós-incidente | sem mudança; o modelo da seção 5 abaixo é o do 261113 com os campos deste intent |
| 6. Recuperação de desastre, metas e procedimento "saque primeiro" | sem mudança (seção 6 abaixo) [Q4] |
| 7. Ensaio de restauração na devnet | sem mudança; o ensaio de rollback ganha as linhas 15 a 18 do `rollback-runbook.md` deste intent (seção 5) |
| 8. AWS Backup | sem mudança |
| 9. SSM Automation e AWS Incident Manager para depois | sem mudança. Este intent não cria documento SSM: as ações dele (desligar a flag, voltar a imagem) são de propósito humanas e com MFA [Q3] |

## 2. Severidades deste intent

### 2.1 Critério P1 somado

Além dos critérios P1 do 261113 (dinheiro em risco, suspeita de comprometimento, saque indisponível): **violação visível de uma regra Forbidden do `project.md`** que misture os modos diante da pessoa, ou seja, total geral PAPER + REAL em tela, API ou relatório (RB-19) [Q1].

### 2.2 Mapeamento: alarme ou sinal → prioridade

| Alarme ou sinal | Sev. do alarme | Prioridade inicial | Muda para | Runbook | Revisão pós-incidente |
|---|---|---|---|---|---|
| `eureka-real-real-ops-pnl-reconciliation-mismatch` | crítica | **P1** | P2 quando a reconciliação da rede da carteira está `CLEAN` (defeito do cálculo, dinheiro seguro); continua P1 se a rede também diverge | RB-16 | obrigatória em até 48 h (P1) ou 5 dias (P2) |
| `eureka-real-real-ops-own-holdings-unavailable` | alta | **P2** | P1 se o saque ou "Vender tudo e sacar" falharem, ou se a flag estiver ligada sem virada registrada | RB-17 | obrigatória, em até 5 dias |
| `eureka-real-real-ops-own-holdings-stuck-bots` | alta | **P2** | idem | RB-17 | obrigatória, em até 5 dias |
| `eureka-real-rds-app-connections` (proposto, D-INF2) | alta | **P2** durante uma troca rolling; **P3** fora dela | P1 se o banco ficar indisponível (RB-11 do 261113) | RB-18 / RB-11 | P2: obrigatória |
| `eureka-real-slo-own-holdings-burn-6h` (proposto, OBS-G6) | média | **P3** | P2 se o alarme de 5% também disparar | RB-17 | opcional |
| bloco PAPER ou REAL em "Erro" (relato; log) | — | **P3** | P2 com os dois blocos fora para todos por mais de 15 min | RB-18 | opcional; obrigatória se P2 |
| total geral PAPER + REAL (relato; CI) | — | **P1** | — | RB-19 | obrigatória, em até 48 h |
| `db-migrate` com saída diferente de zero | — | **P3** (nada foi trocado) | P2 com três falhas iguais ou trava do changelog presa | RB-20 | obrigatória se P2 |
| `e2e` intermitente (TGT-E2E-FLAKY) | — | **fora da escala P**: defeito de CI | — | RB-21 | não se aplica; análise na issue do defeito |

As regras de classificação do 261113 valem: na dúvida, a maior; rebaixar é anotado na linha do tempo; incidente com convidado afetado sobe uma prioridade, até P1.

## 3. Remediação automática (Q3)

**Nada novo automático.** O que já existe e protege este intent sem ninguém agir:

| Remediação | Onde | O que faz | Por que é segura |
|---|---|---|---|
| Circuit breaker do ECS | `api-worker`, `web`, `admin` | uma revisão nova que não fica saudável volta para a anterior | herdada do 261113 |
| Falha fechada da U4 | `OwnHoldingsStep`, `CycleRunner` | sem a leitura da posição própria, o bot não vende pelo ciclo naquele slot e tenta no seguinte | só **deixa de vender**; o saque, "Vender tudo e sacar" e as condicionais não leem a posição própria |
| Bloco isolado na visão por modo | `ModePnlView` (U1, U3) | um bloco que falha sai `UNAVAILABLE` com 200; o outro aparece | nunca troca um modo pelo outro nem soma |
| Bloco REAL sem número parcial | `ReconciliationAuditGate` (U2) | divergência com o ledger vira `UNAVAILABLE` (`RECONCILIATION_MISMATCH`) | o ledger continua a verdade; nada é compensado |
| Espera limitada da migração | `0026` (`lock_timeout` de 5 s); `DROP INDEX CONCURRENTLY IF EXISTS` na `0023` e na `0027` | a migração desiste sem travar o ciclo e a fila; a repetição limpa o índice `INVALID` | a implantação para antes da troca de imagens |

**Nunca automático:** desligar ou ligar a flag da U4 (sempre worker → API para desligar, com `plan`, `apply` e MFA do fundador), ligar ou desligar o kill switch REAL, voltar a imagem depois de uma revisão ficar saudável, compensar o ledger, reverter migração. Um alarme só avisa; nenhum dispara ação.

O IR-G3 limita o diagnóstico na nuvem: não há como rodar SQL. A alternativa A (tarefa avulsa só de leitura, `security-posture-review.md`, 2.1) é decisão do fundador antes de ligar a flag.

## 4. Comunicação (Q4)

Seguem as regras e os modelos M0 a M4 do 261113 (seção 4). Somam-se estas regras:

- **Nunca somar os modos numa mensagem.** Quando um aviso cita P&L, cada modo vem separado e na própria unidade (Simulado em US$, Real em SOL), e o simulado leva o aviso de que é simulado.
- **Nunca prometer resultado.** Um bloco de P&L indisponível ou uma venda que o bot não fez pelo ciclo não vira promessa de recuperação ou de lucro (`project.md`, Forbidden).
- **Canais.** O aviso ao usuário é o aviso no app e o canal dos convidados [Q4]. Os dois ainda não existem: o aviso no app (faixa) é candidato e o canal dos convidados é "a definir" (ESC-8 da `escalation-matrix.md` do 261113). Até existirem, e enquanto o fundador for o único usuário do REAL, o registro é o M0.

**Frequência:** a do 261113 (P1 com convidado afetado: primeiro aviso em até 1 h e atualizações a cada 4 h no máximo; P2: primeiro aviso em até 4 h quando a pessoa perceber; P3: sem aviso).

**M5 — Aviso de exibição incorreta do P&L** (para o RB-19, ou para o RB-16 quando a pessoa percebeu o bloco REAL indisponível por muito tempo).

```
Assunto: Aviso sobre a tela de resultados

Olá, <nome>.

Entre <HH:MM> e <HH:MM> (horário de Brasília) de <data>, a tela de resultados
[mostrou um número que juntava o modo Simulado e o modo Real | não mostrou o bloco do modo Real].
Isso foi um erro de exibição. [Já corrigimos | Estamos corrigindo e avisaremos até <HH:MM>].

Os dois modos são sempre separados: o Simulado (em US$) não usa dinheiro real, e o Real (em SOL)
mostra só as operações da sua carteira do bot. Nenhum valor foi transferido de um modo para o outro.

Seu dinheiro: [conferimos às <HH:MM> e o saldo da sua carteira do bot na rede bate com o nosso registro |
este erro não envolve o saldo da sua carteira do bot].
Saque: o saque para a sua carteira principal registrada continua disponível em Depósito e saque.

Nunca pediremos sua frase de recuperação, chave privada ou uma assinatura para "verificar" a carteira.

<nome do fundador>
```

## 5. Revisão pós-incidente: modelo obrigatório para P1 e P2

Obrigatória para todo P1 (em até 48 h) e todo P2 (em até 5 dias), pela regra da fase Operation. Sem culpa: o foco é o sistema e o processo. Fica no próprio registro do incidente, em `docs/incidents/AAAA-MM-DD-<assunto>.md` (C4 do `runbooks.md` do 261113). É o modelo da seção 5 do 261113 com os campos deste intent, marcados com (261121).

```markdown
# Incidente AAAA-MM-DD — <assunto>

- **Prioridade:** P1 | P2  (rebaixado ou elevado? quando e por quê)
- **Ambiente:** devnet | mainnet
- **Duração:** <início UTC> → <fim UTC> (<h> h <min> min)
- **Runbook usado:** RB-xx (e a seção de `docs/runbooks/real-operations.md` citada)
- **SHA em serviço no início:** <sha>  (261121)
- **Flag da U4 no início:** api <true|false>, worker <true|false>  (261121)
- **Modo afetado:** PAPER | REAL | os dois, cada um descrito à parte; nunca um total somado  (261121)
- **Revisão escrita em:** <data>, por <fundador>

## Resumo
Duas ou três frases: o que aconteceu, o efeito, como terminou.

## Impacto
- Pessoas afetadas: <quantas; nunca nomes nem endereços aqui>
- Saque: indisponível por <tempo> | sempre disponível
- Dinheiro: divergência medida <valor em lamports ou unidades base, como texto decimal> | nenhuma
- P&L (261121): blocos `UNAVAILABLE` por <tempo>, por modo | total geral exibido por <tempo> | nenhum
- Vendas pelo ciclo não feitas (U4) (261121): <quantos ciclos> | nenhuma
- Orçamento de erro consumido: <SLO, % do orçamento> (`slo-config.md` deste intent, seção 3)

## Linha do tempo (UTC)
| Horário | Evento | Fonte |
|---|---|---|
| HH:MM | Alarme `eureka-real-...` em ALARM, ou relato | SNS, relato, CI |
| HH:MM | Fundador reconheceu | — |
| HH:MM | Kill switch ligado / flag do worker desligada / imagem voltada | trilha de auditoria, CloudTrail, nota da implantação |
| HH:MM | ... | ... |
| HH:MM | Serviço normal; REAL e flag como ficaram | trilha de auditoria |

## Detecção
- Como foi detectado e quanto tempo depois do início.
- Algum alarme deveria ter disparado e não disparou? (Lacunas IR-G* do `runbooks.md` deste intent e do 261113.)

## Causa raiz
O motivo técnico. Usar "por quê?" até chegar a uma causa que se possa corrigir.
(261121) Para o total geral: por que a CI não pegou (spec `zz-paper-real-unico`, testes "nenhum terceiro número")?

## Fatores que contribuíram
Processo, ferramenta, documentação ou conhecimento (por exemplo, a IR-G3 impedindo o diagnóstico).

## O que funcionou
## O que pode melhorar

## Prevenção (ações)
| # | Ação | Tipo (código, alarme, runbook, processo) | Dono | Prazo | Situação |
|---|---|---|---|---|---|
| 1 | Teste de regressão que reproduz o defeito (`team.md`) | código | fundador | AAAA-MM-DD | aberta |

## Comunicação
Mensagens enviadas (modelo M0 a M5, horário, a quantas pessoas). Nada de conteúdo pessoal.

## Runbooks atualizados
Quais arquivos de `docs/runbooks/` ou dos `runbooks.md` mudaram por causa deste incidente.
```

Uma ação de prevenção que exige código segue o caminho normal: CI verde, devnet e mainnet.

## 6. RTO e RPO (sem mudança)

| Meta | Valor | Origem |
|---|---|---|
| RPO | até 5 min | PITR do RDS (`incident-plan.md` do 261113, 6.1) |
| RTO | 4 h para a API e o TxSigner no piloto; o ensaio mira 2 h | idem |
| Ordem de volta | o saque é o primeiro serviço a voltar; o REAL fica pausado até a reconciliação limpa | idem |

**Por que nada muda [Q4].** Este intent não muda dados de forma irreversível:
- as migrações `0023` a `0027` só acrescentam (tabelas, regra mais larga e índices), sem remover coluna nem fazer DML em tabela existente (`security-posture-review.md`, SR-03);
- o ledger e as tabelas novas `real_entry_slot` e `real_position_epoch` só recebem inserções;
- o P&L por modo e por bot é calculado a partir do ledger, então uma restauração por PITR o recompõe sem passo próprio;
- a flag da U4 fica no Terraform e no `tfvars`, fora do banco.

Depois de uma restauração, o procedimento do 261113 (seção 6.3) vale como está; o fundador confere ainda que o bloco REAL não sai `RECONCILIATION_MISMATCH` para as carteiras restauradas (RB-16) antes de religar o REAL.

## Sources

- `incident-response-questions.md` (Q1 a Q4, resumo confirmado) [Q1] [Q3] [Q4].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/incident-plan.md` (seções 1 a 9) e `escalation-matrix.md` (ESC-8).
- `runbooks.md` e `escalation-matrix.md` deste estágio.
- `operation/observability-setup/alarms.md` (seções 3 a 5), `slo-config.md` (seções 1, 3 e 4).
- `operation/deployment-pipeline/rollback-runbook.md` (R2, R4, R5, seções 4 a 6) e `cd-config.md` (seção 6).
- `operation/environment-provisioning/security-posture-review.md` (SR-03, 2.1, 2.2).
- `construction/u1-mode-skeleton/nfr-design/reliability-design.md` (bloco isolado), `u2-real-bot-pnl/nfr-design/reliability-design.md`, `u4-own-quantity-cycle/nfr-design/reliability-design.md` (falha fechada), `u3-pnl-views/nfr-design/reliability-design.md`; `infrastructure-design/infrastructure-specification.md` das quatro Units (migrações e rollback).
- `docs/runbooks/real-operations.md` (seções 8 a 13).
- `team.md` (Deployment) e `project.md` (Mandated e Forbidden); regra da fase Operation (Incident Response).
- `.claude/knowledge/aidlc-operations-agent/incident-response-guide.md` (estrutura da revisão pós-incidente).

## Assumptions & Open Questions

1. [assumption] O critério P1 somado (seção 2.1) vem da Q1, que classificou o total geral como P1 por ser violação de regra, mesmo sem dinheiro em risco.
2. [assumption] "Aviso no app e canal dos convidados" (Q4) são canais ainda inexistentes; até o ESC-8 do 261113 ser decidido, a comunicação é o registro M0 do fundador.
3. [assumption] A divergência do P&L REAL começa em P1 (Q1) e pode ser rebaixada para P2; o `alarms.md` e o `rollback-runbook.md` a davam como P2. Ver `runbooks.md`, Assumptions item 1.
4. Aberta: segundo contato "a definir" (ESC-6 do 261113), condição antes dos convidados.
5. Aberta: IR-G3, decisão do fundador antes da virada da flag da U4.
