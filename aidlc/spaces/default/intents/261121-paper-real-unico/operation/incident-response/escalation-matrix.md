# Matriz de escalonamento — intent `261121-paper-real-unico`

Conversa: português (Brasil). Estágio: Incident Response, Q2 (opção A), com o resumo confirmado. Data: 2026-11-25.

> **Nada está implantado.** Os canais passam a valer depois do primeiro `apply` da devnet e da confirmação manual da assinatura de e-mail do tópico. **Nenhum telefone, e-mail ou nome foi inventado:** o que não está decidido aparece como "a definir", e o valor fica com o responsável do projeto, fora do repositório.

## 1. Base: a matriz do 261113, sem mudança

Vale **inteira** a `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/escalation-matrix.md`:

| Seção do 261113 | Situação aqui |
|---|---|
| 1. Plantão | sem mudança: o fundador é o único on-call e comandante; segundo contato "a definir" [Q2] |
| 2. Rotas por severidade | sem mudança; os alarmes novos casam com as rotas existentes (seção 3 abaixo) |
| 3. SNS: tópico único `eureka-real-alarms`, só e-mail hoje; texto pronto para o SMS (IR-G8) | sem mudança; um alarme proposto pede uma linha na lista "só e-mail" (seção 4) |
| 4. Contatos externos (AWS, RPC, Jupiter, CoinGecko, assessor jurídico, GitHub, DNS) | sem mudança; nenhum contato novo neste intent |
| 5. Segundo contato | sem mudança: "a definir", condição antes dos convidados |
| 6. Escalonamento por tempo | sem mudança; vale para os P1 e P2 deste intent |
| 7. Itens em aberto ESC-1 a ESC-8 | sem mudança; este intent soma ESC-9 e ESC-10 |

## 2. Plantão e contato

| Papel | Quem | Canal de contato | Disponibilidade | Pode decidir |
|---|---|---|---|---|
| On-call único, comandante do incidente | **fundador** (responsável do projeto) | e-mail do tópico `eureka-real-alarms` (`var.alarm_email`, valor fora do repositório, ESC-1); SMS para crítica e alta quando o ESC-2 for aplicado (`alarm_sms_number`, fora do repositório) | 24x7 no piloto | tudo, inclusive desligar a flag da U4 (worker → API), voltar a imagem e o kill switch REAL |
| Segundo contato | **a definir** (ESC-6) | a definir | a definir | a definir; recomendação do 261113: só ligar o kill switch REAL e acordar o fundador |
| Dono do defeito de CI (RB-21) | responsável do projeto (o mesmo fundador) | issues do repositório privado `rafaelcruz-net/eureka-trade-ai` | horário normal | alterar `scripts/verify.sh` e `.github/workflows/` (só ele) |

- **Implantação na mainnet:** só começa quando o fundador pode acompanhar a janela de observação de 10 min e os 30 min seguintes (`team.md`, Deployment) [Q2]. Isso vale também para a implantação só da flag da U4 (RB-17).
- **Sem o fundador disponível**, o sistema se contém: a flag da U4 está desligada por padrão e a U4 falha fechada; o bloco REAL sai `UNAVAILABLE` em vez de mostrar número errado; a migração desiste sem trocar imagem; o saque continua disponível.
- O número de telefone e o e-mail são dados pessoais: não entram no repositório, nos registros de incidente nem nos logs.

## 3. Alarmes deste intent: severidade e rota

Severidade do alarme (canal) do `alarms.md` deste intent, seção 4; prioridade do incidente (resposta) do `incident-plan.md`, seção 2.2.

| Alarme | Estado | Sev. | Rota (canal) | Prioridade | Reconhecer em | Runbook |
|---|---|---|---|---|---|---|
| `eureka-real-real-ops-pnl-reconciliation-mismatch` | no repositório | crítica | **Alta**: e-mail, e SMS com o ESC-2 (casa com `real-ops-*`) | P1 (P2 com a rede `CLEAN`) | 15 min | RB-16 |
| `eureka-real-real-ops-own-holdings-unavailable` | no repositório; OK com a flag desligada | alta | **Alta**: e-mail, e SMS com o ESC-2 | P2 | 1 h | RB-17 |
| `eureka-real-real-ops-own-holdings-stuck-bots` | no repositório; OK com a flag desligada | alta | **Alta**: e-mail, e SMS com o ESC-2 | P2 | 1 h | RB-17 |
| `eureka-real-rds-app-connections` (D-INF2, opção c) | **proposto**, decisão do fundador | alta | **Alta**: fora da lista "só e-mail", vai por SMS por padrão | P2 na troca; P3 fora dela | 1 h | RB-18 / RB-11 do 261113 |
| `eureka-real-slo-own-holdings-burn-6h` (OBS-G6) | **proposto**, junto com a F7 e antes da virada | média | **só e-mail**; exige entrar na lista `alarm_email_only` (ESC-9) | P3 | 2 dias úteis | RB-17 |

**Sinais sem alarme** (não passam pelo tópico):

| Sinal | Quem vê | Prioridade | Runbook |
|---|---|---|---|
| bloco PAPER ou REAL em "Erro"; consulta `pnl-blocos-indisponiveis` | pessoa (relato), fundador | P3 | RB-18 |
| total geral PAPER + REAL | pessoa (relato), fundador, CI | P1 | RB-19 |
| `db-migrate` com saída diferente de zero | fundador, no run da implantação | P3 (P2 com 3 falhas ou trava presa) | RB-20 |
| job `e2e` vermelho | e-mail do GitHub Actions ao responsável | defeito de CI | RB-21 |

Todos os alarmes, novos e propostos, mandam para o mesmo tópico `eureka-real-alarms`; nenhum tem `ok_actions` (só o batimento tem). A janela de observação de 10 min de cada implantação aborta com qualquer alarme `eureka-real-*` em ALARM, os novos inclusive.

## 4. Escalonamento por tempo (os mesmos tempos do 261113)

| Prioridade | 0 min | 15 min | 30 min | 4 h | 24 h |
|---|---|---|---|---|---|
| **P1** (RB-16, RB-19) | e-mail (e SMS com o ESC-2) ao fundador; relato ou CI no RB-19 | fundador reconhece; kill switch REAL só se houver dinheiro em risco (RB-16 com a rede divergente); RB-19: decide voltar a imagem | sem reconhecimento: segundo contato (quando existir) | imagem voltada ou divergência entendida; aviso M5 a convidados afetados (até 1 h) | sem solução e com convidado afetado: avaliar com o assessor jurídico (a definir) |
| **P2** (RB-17, RB-20 com 3 falhas) | e-mail (e SMS) ao fundador | — | — | fundador mitigou (flag do worker desligada, depois a API) ou registrou por que não | — |
| **P3** (RB-18, RB-20 na 1ª falha) | e-mail ou relato | — | — | — | tratado em até 2 dias úteis |
| Defeito de CI (RB-21) | e-mail do GitHub | — | — | repetição registrada na issue | defeito investigado, sem prazo de plantão |

Todo P1 e P2 termina com a revisão pós-incidente (`incident-plan.md`, seção 5).

## 5. Contatos externos

Os do 261113 (seção 4), sem contato novo. Os que este intent pode acionar:

| Contato | Quando, neste intent | Situação |
|---|---|---|
| Suporte da AWS (RDS) | `BUDGET_EXCEEDED` sistêmico com o RDS `app` saturado (RB-17); conexões no teto (D-INF2); RDS fora (RB-11 do 261113) | plano a definir (ESC-3) |
| AWS Health Dashboard | qualquer suspeita de problema regional | disponível em toda conta |
| GitHub (status público `www.githubstatus.com`) | Actions fora durante uma implantação ou o rollback (caminho C do 261113) | disponível |
| Assessor jurídico | total geral visto por convidados, ou divergência com dinheiro de convidado | a definir (ESC-7) |

Ao abrir chamado com fornecedor: nunca enviar chave, token, segredo, endereço completo de carteira, dado pessoal ou captura de tela com valores de P&L de pessoas. Só horário UTC, código de erro, rota e o `correlationId` quando ajudar.

## 6. Itens em aberto para o responsável

Os ESC-1 a ESC-8 do 261113 continuam valendo. Somam-se:

| ID | O quê | Antes de |
|---|---|---|
| **ESC-9** | Ao aplicar o alarme da OBS-G6, acrescentar `slo-own-holdings-burn-6h` à lista `alarm_email_only` do texto pronto do SMS (seção 3.2 da matriz do 261113), para ele ir só por e-mail | virada da flag da U4 |
| **ESC-10** | Ao aplicar o alarme da D-INF2 (opção c), escrever o runbook próprio dele (`alarms.md`, 5.1) e conferir a rota por SMS (fica fora da lista "só e-mail" de propósito) | primeira implantação na nuvem |
| ESC-6 (herdado) | Segundo contato: quem, canal, permissões e treino | convidados |
| ESC-1 e ESC-2 (herdados) | E-mail do tópico e SMS do plantão, fora do repositório | devnet e mainnet |

## Sources

- `incident-response-questions.md` (Q2; Q1 e Q4 para as prioridades e a comunicação) [Q1] [Q2] [Q4].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/incident-response/escalation-matrix.md` (seções 1 a 7) e `incident-plan.md` (seções 1 e 4).
- `operation/observability-setup/alarms.md` (seções 3.1, 4, 5.1, 5.2 e 6) e `dashboards.md` (seção 2).
- `operation/deployment-pipeline/rollback-runbook.md` (seção 6: escalonamento e contatos "a definir") e `deployment-strategy.md` (1.3, janela de observação).
- `operation/environment-provisioning/security-posture-review.md` (2.1 e 2.2).
- `construction/u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md` (D-INF2) e `construction/u4-own-quantity-cycle/infrastructure-design/infrastructure-specification.md` (flag, F4); `nfr-design/reliability-design.md` e `security-design.md` das Units U1 a U4.
- `team.md` (Deployment: fundador único on-call, janela mais 30 min; Way of Working: repositório privado, só o responsável altera `.github/workflows/`) e `project.md` (Forbidden: segredos e dados fora de logs).
- `.claude/knowledge/aidlc-operations-agent/incident-response-guide.md` (escalonamento e rodízio).

## Assumptions & Open Questions

1. [assumption] A prioridade P1 da divergência do P&L REAL (Q1) prevalece sobre a P2 da seção 4 do `alarms.md`; o canal (crítica, rota "Alta") não muda.
2. [assumption] O alarme proposto da D-INF2 entra como "alta" na rota de SMS, como está no `alarms.md` (5.1); se o fundador preferir só e-mail fora da troca, ele entra na lista `alarm_email_only`.
3. Aberta: segundo contato (ESC-6), "a definir".
4. Aberta: valores de `alarm_email` e `alarm_sms_number` (ESC-1, ESC-2), fora do repositório.
5. Aberta: ESC-9 e ESC-10, que dependem das decisões OBS-G6 e D-INF2 do fundador.
