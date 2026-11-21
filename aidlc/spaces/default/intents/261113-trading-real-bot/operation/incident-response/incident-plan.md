# Plano de resposta a incidentes — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Incident Response. Decisões: `incident-response-questions.md`, Q1 a Q5, todas na opção A, com o resumo confirmado.

> **Nada está implantado.** Não existe conta AWS. Este plano vale a partir do primeiro `apply` da devnet. O ensaio de restauração (seção 7) e o ensaio de rollback (`rollback-runbook.md`, seção 8) são as primeiras provas dele.

Peças deste estágio:
- `runbooks.md`: um runbook por modo de falha (RB-01 a RB-15) e a tabela alarme → runbook;
- este plano: severidades, ciclo de vida, comunicação, revisão pós-incidente, remediação automática e recuperação de desastre;
- `escalation-matrix.md`: plantão, rotas do SNS e contatos.

## 1. Severidades

| Prioridade | Critério | Exemplos | Reconhecer | Primeira ação | Revisão pós-incidente |
|---|---|---|---|---|---|
| **P1** | Dinheiro em risco, suspeita de comprometimento, ou **saque indisponível** | recusa da política do TxSigner; divergência sem causa; alarme `-security-*` sem passo do fundador; achado alto do GuardDuty; saque parado ou recusado; TxSigner, KMS, banco ou os dois RPC fora | até 15 min depois do SMS | **kill switch REAL** (C1) em até 5 min, se houver dinheiro em risco; depois o runbook | obrigatória, em até 48 h |
| **P2** | REAL parado ou degradado, dinheiro seguro e saque funcionando | ordem ambígua; preço parado; Jupiter fora; trabalho do caminho do dinheiro parado; implantação com falha depois da troca; métricas ausentes com o processo vivo | até 1 h | o runbook | obrigatória, em até 5 dias |
| **P3** | Sem efeito em dinheiro nem em saque | orçamento, custo, valores inválidos, anomalias, liberação bloqueada, trabalho de expurgo parado, p95 | até 2 dias úteis | o runbook, no horário normal | opcional |

Regras de classificação:
- Na dúvida entre duas prioridades, vale a maior. Rebaixar é uma decisão anotada na linha do tempo.
- Um alarme "crítica" (canal) pode virar P2 ou P3 (resposta), e o contrário também: a tabela de cobertura do `runbooks.md` dá a prioridade de cada alarme.
- Qualquer incidente com convidado afetado sobe uma prioridade, até P1.
- Na mainnet do fundador (etapa 3 do `cd-config.md`, seção 6), um P1 que envolva divergência reinicia a contagem dos 7 dias exigidos antes dos convidados.

## 2. Ciclo de vida: detectar → triar → mitigar → comunicar → recuperar → revisar

| Fase | O que acontece | Quem e como | Saída |
|---|---|---|---|
| **1. Detectar** | Um alarme chega pelo tópico `eureka-real-alarms` (e-mail; SMS para crítica e alta quando o SMS existir), um achado do GuardDuty ou do Access Analyzer chega pelo EventBridge, ou uma pessoa relata | automático; o fundador recebe | horário da detecção (UTC) |
| **2. Triar** | Rodar `alarms` (`runbooks.md`, seção 0); achar o runbook na tabela de cobertura; decidir a prioridade (seção 1); abrir o registro (C4) | fundador | prioridade, runbook, registro aberto |
| **3. Mitigar** | Dinheiro em risco: **kill switch primeiro** (C1; C2 se a administração não responde). Depois a mitigação do runbook. O saque nunca é desligado | fundador | dano contido; horário de cada ação |
| **4. Comunicar** | No piloto do fundador, o fundador registra para si mesmo (modelo M0). Com convidados, aviso aos afetados (M1), atualizações (M2, M4) e encerramento (M3) | fundador | mensagens enviadas e anotadas |
| **5. Recuperar** | Recuperação e verificação do runbook; religar o REAL só pelas condições do C5 | fundador | serviço normal; REAL religado ou mantido desligado com motivo |
| **6. Revisar** | Revisão pós-incidente (seção 5) para todo P1 e P2; ações de prevenção com prazo; todo defeito corrigido ganha teste de regressão (`team.md`) | fundador | revisão registrada; ações abertas |

**Durante o incidente, o comandante, quem investiga e quem comunica são a mesma pessoa.** Por isso a ordem é fixa: conter primeiro (kill switch), registrar o horário, depois investigar. A comunicação acontece nos momentos marcados (início, a cada atualização prometida, fim), não durante a investigação.

**Sem o fundador disponível** (dormindo sem ver o SMS, em viagem), o sistema se contém sozinho: a barreira falha fechada, a divergência pausa a carteira, a restauração pausa o REAL, a fonte parada recusa compras, e o saque continua. O segundo contato, que é condição antes dos convidados, está em `escalation-matrix.md`, seção 5.

## 3. Remediação automática (Q3)

Só o que é seguro e reversível roda sozinho. Tudo já existe no código ou na IaC; este estágio não acrescenta automação.

| Remediação | Onde | O que faz | Por que é segura |
|---|---|---|---|
| Circuit breaker do ECS | `deployment_circuit_breaker` dos serviços (`cd-config.md`, seção 5) | uma revisão nova que não fica saudável volta para a anterior | volta para uma versão que já estava em serviço; não mexe em dados |
| Reinício de tarefa não saudável | serviço ECS e verificação de saúde | o ECS troca a tarefa que falha na verificação | o estado vive no banco; os trabalhos retomam do estado gravado |
| Pausas globais e por carteira do código | U3, U4, U5 | `DIVERGENCE` (carteira), `RESTORE_CHECK_PENDING` e `RESTORE` (global), `UPSTREAM` (pessoa), `DAILY_LOSS` (carteira), barreira em falha fechada | só **param** ordens novas; nenhuma move dinheiro; o saque continua |
| Retomadas automáticas do código | U3, U4 | `UPSTREAM` sai depois de 5 min saudável; `DAILY_LOSS` sai às 00:00 UTC | voltam a um estado que as regras já permitem; nenhuma retoma `DIVERGENCE` nem `RESTORE` |
| Repetições idempotentes | U1, U4, U5, U7 | saque com espera crescente (5 s até 5 min); `ConfirmationJob` com a mesma transação enquanto o blockhash vale; `TxSignerCommandJob` (`LOWER_CAP`, `DEACTIVATE`) até confirmar; `ConditionalCancelJob` | a mesma operação, com a mesma chave; nenhuma gera transação nova |
| Circuitos do Resilience4j | U1, U2, U4 | TxSigner, RPC (troca de provedor), KMS, CoinGecko, Jupiter | só param chamadas |

**Nunca automático:** ligar ou desligar o kill switch, desligar o saque, compensar uma divergência, confirmar uma restauração, liberar ou revogar uma pessoa, voltar imagem depois de uma revisão ficar saudável, reverter migração, mexer em chave do KMS ou em papel do IAM. Um alarme nunca dispara uma ação: ele só avisa.

## 4. Comunicação durante o incidente (Q4)

### 4.1 Regras

- Mensagens em português, em texto simples, enviadas pelo fundador.
- **Canal com os convidados:** a definir pelo fundador antes de abrir aos convidados (e-mail ou mensagem direta). Um aviso no app (faixa) é candidato para depois.
- Toda mensagem diz: **o que aconteceu**, **se o dinheiro está seguro**, **se o saque continua disponível** e **quando vem o próximo aviso**.
- **Nunca** prometer prazo de lucro, recuperação de perda nem resultado; nunca garantir detecção de fraude (`project.md`, Forbidden).
- Nunca afirmar que o dinheiro está seguro sem ter conferido. Use a frase conferida ou a frase de dúvida dos modelos.
- Nunca citar causa antes de confirmada; nunca citar endereço de carteira, valores de outras pessoas, segredo ou nome de fornecedor como culpado.
- Nunca pedir à pessoa chave, frase de recuperação, assinatura ou "verificação de carteira". A mensagem lembra isso, porque golpistas imitam avisos de incidente.
- Uma mensagem por pessoa afetada, sem cópia aberta para outros convidados.

### 4.2 Modelos

Os campos entre `<>` são preenchidos na hora. Os trechos entre colchetes têm uma opção para escolher.

**M0 — Registro do fundador (piloto sem convidados).** Primeira linha do registro do incidente (C4):

```
[P1|P2|P3] <AAAA-MM-DD HH:MM UTC> <assunto em poucas palavras>
Detectado por: <alarme | relato | painel>. Runbook: <RB-xx>.
Dinheiro: [conferido: saldos da rede batem com o ledger | não conferido ainda].
Saque: [funcionando | parado desde <HH:MM UTC>].
Kill switch: [ligado às <HH:MM UTC> | não ligado, porque <motivo>].
Próxima conferência: <HH:MM UTC>.
```

**M1 — Aviso inicial ao convidado afetado.**

```
Assunto: Aviso sobre o seu bot no modo REAL

Olá, <nome>.

Às <HH:MM> (horário de Brasília) de <data>, identificamos <descrição curta e factual, por exemplo: "uma falha na
comunicação com a rede Solana" | "uma diferença entre o saldo registrado e o saldo da sua carteira do bot">.

O que fizemos: pausamos as novas operações automáticas do modo REAL enquanto investigamos.

Seu dinheiro: [conferimos às <HH:MM> e o saldo da sua carteira do bot na rede bate com o nosso registro |
ainda estamos conferindo o saldo da sua carteira do bot e avisaremos assim que terminarmos].

Saque: [o saque para a sua carteira principal registrada continua disponível em Depósito e saque |
o saque está atrasado por causa desta falha; o pedido fica registrado e é processado assim que ela for resolvida].

Próximo aviso: até <HH:MM> de <data>, mesmo que ainda não haja solução.

Nunca pediremos sua frase de recuperação, chave privada ou uma assinatura para "verificar" a carteira.
Se receber um pedido assim, não responda.

<nome do fundador>
```

**M2 — Atualização.**

```
Assunto: Atualização sobre o aviso de <data>

Olá, <nome>.

Situação agora: [ainda investigando | causa identificada e em correção | corrigido, em conferência].
<uma ou duas frases factuais, sem especulação>

Seu dinheiro: <frase conferida ou frase de dúvida, como no primeiro aviso>.
Saque: <disponível | atrasado, com o motivo>.
As operações automáticas do modo REAL continuam pausadas.

Próximo aviso: até <HH:MM> de <data>.

<nome do fundador>
```

**M3 — Encerramento.**

```
Assunto: Resolvido: aviso de <data>

Olá, <nome>.

A falha de <data> foi resolvida às <HH:MM> (horário de Brasília).

O que aconteceu: <causa confirmada, em linguagem simples>.
Seu dinheiro: conferimos às <HH:MM> e o saldo da sua carteira do bot na rede bate com o nosso registro.
Saque: disponível.
Operações automáticas: [voltaram a ser permitidas; o seu bot só volta a operar quando você o religar |
continuam pausadas até <condição>].

O que vamos mudar para evitar que se repita: <uma frase, sem prazo prometido>.

<nome do fundador>
```

**M4 — Saque atrasado.**

```
Assunto: Seu saque de <data>

Olá, <nome>.

Seu pedido de saque de <data> às <HH:MM> ainda não foi concluído por causa de <descrição curta e factual>.
O valor continua reservado na sua carteira do bot e só pode ir para a sua carteira principal registrada.
Nenhuma outra pessoa e nenhum outro endereço podem receber esse valor.

Você não precisa pedir de novo. [O pedido será concluído assim que <condição> | Se o pedido for cancelado
por tempo, o valor volta a ficar disponível e você poderá pedir outra vez].

Próximo aviso: até <HH:MM> de <data>.

Nunca pediremos sua frase de recuperação, chave privada ou uma assinatura para liberar um saque.

<nome do fundador>
```

**Frequência:** P1 com convidado afetado, primeiro aviso em até 1 h e atualizações a cada 4 h no máximo; P2, primeiro aviso em até 4 h quando a pessoa perceber o efeito; P3, sem aviso.

## 5. Revisão pós-incidente

Obrigatória para todo P1 (em até 48 h) e todo P2 (em até 5 dias), pela regra da fase Operation. Sem culpa: o foco é o sistema e o processo. Ela fica no próprio registro do incidente, em `docs/incidents/AAAA-MM-DD-<assunto>.md`.

### Modelo

```markdown
# Incidente AAAA-MM-DD — <assunto>

- **Prioridade:** P1 | P2  (rebaixado ou elevado? quando e por quê)
- **Ambiente:** devnet | mainnet
- **Duração:** <início UTC> → <fim UTC> (<h> h <min> min)
- **Runbook usado:** RB-xx (e o `docs/runbooks/*.md` citado)
- **Revisão escrita em:** <data>, por <fundador>

## Resumo
Duas ou três frases: o que aconteceu, o efeito, como terminou.

## Impacto
- Pessoas afetadas: <quantas; nunca nomes nem endereços aqui>
- Saque: indisponível por <tempo> | sempre disponível
- Dinheiro: divergência medida <valor em lamports ou unidades base, como texto decimal> | nenhuma
- Ordens: <quantas canceladas, ambíguas, falhas>
- Orçamento de erro consumido: <SLO, minutos ou %> (`slo-config.md`, seção 3)

## Linha do tempo (UTC)
| Horário | Evento | Fonte |
|---|---|---|
| HH:MM | Alarme `eureka-real-...` em ALARM | SNS |
| HH:MM | Fundador reconheceu | — |
| HH:MM | Kill switch ligado (motivo: "...") | trilha de auditoria |
| HH:MM | ... | ... |
| HH:MM | REAL religado / mantido desligado | trilha de auditoria |

## Detecção
- Como foi detectado e quanto tempo depois do início (tempo até detectar).
- Algum alarme deveria ter disparado e não disparou? (Ver as lacunas IR-G* do `runbooks.md`.)

## Causa raiz
O motivo técnico. Usar "por quê?" até chegar a uma causa que se possa corrigir.

## Fatores que contribuíram
Processo, ferramenta, documentação ou conhecimento que deixaram o problema acontecer ou demorar.

## O que funcionou
## O que pode melhorar

## Prevenção (ações)
| # | Ação | Tipo (código, alarme, runbook, processo) | Dono | Prazo | Situação |
|---|---|---|---|---|---|
| 1 | Teste de regressão que reproduz o defeito (`team.md`) | código | fundador | AAAA-MM-DD | aberta |

## Comunicação
Mensagens enviadas (modelo, horário, a quantas pessoas). Nada de conteúdo pessoal.

## Runbooks atualizados
Quais arquivos de `docs/runbooks/` ou do `runbooks.md` mudaram por causa deste incidente.
```

Uma ação de prevenção que exige código segue o caminho normal: CI verde, devnet e mainnet. Um runbook que estava errado é corrigido na mesma semana.

## 6. Recuperação de desastre (Q5)

### 6.1 Metas

| Meta | Valor | Origem |
|---|---|---|
| **RPO** | até 5 min | recuperação a um ponto no tempo (PITR) do RDS, `backup_retention_period = 7` em `infra/terraform/modules/data/main.tf`; U1:NFR4.8 |
| **RTO** | 4 h para a API e o TxSigner no piloto; o ensaio mira 2 h | `reliability-design.md` da U1 (NFR4.9) |
| Cópia diária | 30 dias de guarda (AWS Backup) | seção 8 |
| **Ordem de volta** | **o saque é o primeiro serviço a voltar**; o REAL fica pausado até a reconciliação limpa | Q5 |

Os dois bancos (`eureka-real-app` e `eureka-real-txsigner`) ficam numa zona só (`multi_az = false`): a perda da zona é recuperada por restauração, dentro do RTO de 4 h, e não por réplica (Q5, opção B recusada).

### 6.2 Cenários

| Cenário | Procedimento |
|---|---|
| D1. Tarefa ou serviço perdido | remediação automática do ECS (seção 3); se não voltar, `runbooks.md` RB-03, RB-12 ou RB-13 |
| D2. Banco da aplicação perdido ou corrompido | seção 6.3, passos A, C, D e E |
| D3. Banco do TxSigner perdido ou corrompido | seção 6.3, passos A, B, D e E, com `docs/runbooks/txsigner-recovery.md` |
| D4. Os dois bancos, ou a zona dos dois | seção 6.3 inteira |
| D5. Região ou conta AWS perdida | **fora do RTO.** As chaves das carteiras ficam no KMS, não exportáveis e numa região só (`project.md`, Mandated; SP-13 pede `MultiRegion = false`). Perder a conta ou a região perde o acesso aos fundos das carteiras do bot. É o risco aceito do desenho (ADR-005): ele é limitado pelo teto de até 10 SOL por carteira, pelo piloto de até 20 convidados e pelas proteções contra exclusão de chave (SP-14). A revisão desse risco fica para o crescimento do produto |

### 6.3 Procedimento de restauração (saque primeiro)

Com o perfil de administração com MFA. Anotar cada horário em UTC: é a medida do RTO.

**A. Conter e decidir o ponto de restauração.**
1. Kill switch REAL (C1), se a API ainda responde. Se não responde, o próprio código pausa o REAL depois da restauração (`RESTORE_CHECK_PENDING`).
2. Escolher o instante `T` (UTC) para cada banco: o último instante antes da perda ou da corrupção, até o `LatestRestorableTime`:
   ```bash
   for db in app txsigner; do aws rds describe-db-instances --db-instance-identifier eureka-real-$db \
     --query 'DBInstances[0].[DBInstanceIdentifier,LatestRestorableTime]' --output text; done
   ```
   Quando os dois bancos são restaurados, usar o mesmo `T` nos dois sempre que possível.
3. Parar a escrita: `aws ecs update-service --cluster eureka-real --service api-worker --desired-count 0`. O TxSigner é parado pelo passo do fundador (`cd-config.md` 4.6), com `desired-count 0`.

**B. Banco do TxSigner (D3, D4).**
1. Restaurar para uma instância nova:
   ```bash
   aws rds restore-db-instance-to-point-in-time \
     --source-db-instance-identifier eureka-real-txsigner \
     --target-db-instance-identifier eureka-real-txsigner-r$(date -u +%Y%m%d%H%M) \
     --restore-time <T em ISO-8601 UTC> \
     --db-subnet-group-name <o grupo de subredes do txsigner, do estado do Terraform> \
     --vpc-security-group-ids <o grupo de segurança do banco do txsigner> \
     --db-parameter-group-name <o grupo de parâmetros postgres do Terraform> \
     --no-publicly-accessible --deletion-protection --copy-tags-to-snapshot
   aws rds wait db-instance-available --db-instance-identifier eureka-real-txsigner-r<carimbo>
   ```
   Os valores entre `<>` saem de `aws rds describe-db-instances --db-instance-identifier eureka-real-txsigner` (instância original) ou do estado do Terraform.
2. Trocar os nomes, para o endereço (DNS) do banco continuar o mesmo e nenhuma configuração mudar: renomear a original para `eureka-real-txsigner-old-<carimbo>` e a restaurada para `eureka-real-txsigner` (`aws rds modify-db-instance --db-instance-identifier <atual> --new-db-instance-identifier <novo> --apply-immediately`, uma de cada vez, esperando `available`). A original fica até o fim da revisão pós-incidente, com proteção contra exclusão.
3. Alinhar o estado do Terraform com a instância restaurada antes do próximo `apply` (`terraform state rm` e `terraform import` do recurso `module.data.aws_db_instance.main["txsigner"]`). O comando exato é conferido no ensaio (seção 7).
4. Subir o TxSigner em **modo de recuperação**: `TXSIGNER_RECOVERY=true` na definição de tarefa (Terraform, `apply` com MFA) e o passo do fundador (`cd-config.md` 4.6) com o SHA em serviço. Nos primeiros 5 min nada é assinado; depois, só saques (`docs/runbooks/txsigner-recovery.md`, seção 1).
5. Registrar as políticas perdidas (`POST /internal/v1/recovery/wallets`) e concluir (`POST /internal/v1/recovery/complete`). **Lacuna IR-G7:** hoje nenhum cliente faz essas chamadas. Até ela fechar, a carteira cuja política se perdeu fica sem saque, e o cenário D3 não cumpre "saque primeiro". Por isso a IR-G7 bloqueia a mainnet.

**C. Banco da aplicação (D2, D4).** Os mesmos passos B.1 a B.3 com `eureka-real-app` e o recurso `module.data.aws_db_instance.main["app"]`.

**D. Voltar com o saque primeiro.**
1. Subir o `api-worker` (`desired-count 1`). Na subida, o worker abre `RESTORE_CHECK_PENDING` antes de qualquer leitura: o REAL fica pausado para todos, e o saque funciona.
2. **Conferir o saque** antes de qualquer outra coisa:
   - devnet: saque pequeno da carteira do bot de teste para a carteira principal de teste;
   - mainnet: pela tela "Depósito e saque" da carteira do fundador, só se ele decidir (fundos reais).
   - `metric realwallet_withdrawal_oldest_pending_seconds Maximum Name=state,Value=REQUESTED` caindo.
   Este é o marco do RTO: **saque funcionando**.
3. Acompanhar a conferência da restauração (`docs/runbooks/real-operations.md`, seção 4): sem nada desconhecido, a pausa sai sozinha; com assinatura desconhecida, a pausa vira `RESTORE`.

**E. Reconciliar e só então religar o REAL.**
1. Esperar a reconciliação `AFTER_RESTORE` de todas as carteiras. Divergências abertas seguem o `runbooks.md`, RB-02 (compensação só com causa conhecida; o ledger nunca é editado).
2. Com todas as carteiras `CLEAN`: confirmar a restauração na administração (`POST /api/v1/admin/real/restore/confirmation`, motivo). Com alguma não limpa, a API responde `409 RESTORE_NOT_CLEAN`.
3. Tirar `TXSIGNER_RECOVERY` na próxima subida do TxSigner (Terraform e 4.6).
4. As pessoas das carteiras restauradas no TxSigner precisam de um consentimento novo para voltar ao teto e ao modo (`policyResetRequired`).
5. Religar o REAL só pelo C5 (decisão do fundador).
6. Revisão pós-incidente (seção 5): restauração completa conta como desastre e consome o orçamento de erro do mês.

### 6.4 Orçamento de tempo (estimativa até o ensaio)

| Etapa | Estimativa | Medida no ensaio |
|---|---|---|
| A. Conter e escolher `T` | 15 min | — |
| B/C. Restauração PITR de um `db.t4g.micro` pequeno, em paralelo nos dois bancos | 30 a 60 min | — |
| B/C. Troca de nomes e Terraform | 20 min | — |
| B.4. Modo de recuperação do TxSigner (apply e 4.6) | 20 a 30 min, mais os 5 min sem assinatura | — |
| D. Subida e saque conferido | 15 min | — |
| E. Reconciliação e confirmação | 15 a 60 min | — |
| **Total até o saque funcionar** | **cerca de 1 h 40 min a 2 h 30 min** | — |

A coluna "Medida no ensaio" é preenchida na seção 7. Se o ensaio passar de 4 h, o RTO não está cumprido, e a mainnet não abre.

## 7. Ensaio de restauração na devnet (antes da mainnet)

O `reliability-design.md` da U1 pede as duas restaurações ensaiadas antes da mainnet. O ensaio é feito na devnet, junto com o ensaio de rollback (`rollback-runbook.md`, seção 8), porque os dois usam o mesmo ambiente.

**Pré-condições:** devnet implantada e saudável (H1 a H22 do `validation-report.md`); carteira do bot de teste ativa com SOL da devnet; pelo menos um ponto de recuperação diário `COMPLETED` (H17); lacuna IR-G7 fechada, ou o passo E5 registrado como "não cumprido".

| # | Ação | Esperado | Evidência |
|---|---|---|---|
| 1 | Anotar `T0` (UTC). Fazer um depósito e uma ordem REAL pequenos na devnet | ordem `CONFIRMED`; reconciliação `CLEAN` | horário; captura |
| 2 | Depois de `T0`, fazer um **saque** pequeno | saque `CONFIRMED` | horário |
| 3 | Seção 6.3, A e C: restaurar o banco da aplicação para `T0` | instância restaurada `available`; RPO medido = relógio menos `LatestRestorableTime` no momento da restauração | saída dos comandos; tempo |
| 4 | Seção 6.3, D.1: subir o `api-worker` | `RESTORE_CHECK_PENDING` abre; o saque de 2 é desconhecido → a pausa vira `RESTORE` (`real-ops-restore-pause` dispara) | alarme recebido; métrica `real_restore_pause_active` |
| 5 | Seção 6.3, D.2: saque de prova durante a pausa | saque aceito e `CONFIRMED` (limitado ao que a rede confirma) | captura; **horário = RTO do saque** |
| 6 | Seção 6.3, E.1: reconciliação | divergência da diferença do saque de 2; compensação pela administração; `CLEAN` | captura; evento na trilha |
| 7 | Seção 6.3, E.2: confirmar a restauração | pausa sai; `409 RESTORE_NOT_CLEAN` antes da compensação, `200` depois | respostas |
| 8 | Anotar `T1` (UTC). Depois de `T1`, ativar uma **segunda carteira do bot** de teste e depositar nela. Então, seção 6.3, A e B: restaurar **só** o banco do TxSigner para `T1` | a política da segunda carteira não existe no banco restaurado; o banco da aplicação continua com ela | saída dos comandos |
| 9 | Seção 6.3, B.4 e B.5: modo de recuperação e registro das políticas | 5 min sem assinatura (`503 RECOVERY_IN_PROGRESS`); depois, saque da segunda carteira assinado, com teto 0 e modo Assinada; criação de carteira recusada | logs; respostas |
| 10 | Ensaio de bloqueio: registrar uma política com a carteira principal errada | `422 RECOVERY_REFUSED`, evento `WALLET_BLOCKED`; o saque das outras carteiras continua | evento; resposta |
| 11 | Restauração a partir do **AWS Backup** (ponto diário), numa instância à parte, só para provar o caminho: `aws backup start-restore-job` | instância `available` com os dados do dia; apagada depois do ensaio | saída do comando |
| 12 | Terraform: `terraform plan` depois de alinhar o estado | nenhuma mudança destrutiva nos bancos | saída do plan |
| 13 | C5: religar o REAL | ordens voltam; reconciliação `CLEAN` | captura |

**Critério de aprovação:**
- passos 1 a 13 com o resultado esperado;
- nenhum saque recusado ou perdido;
- **saque funcionando em até 4 h** do início (meta do ensaio: 2 h);
- RPO medido de até 5 min;
- o comando exato do Terraform do passo B.3 anotado e copiado para este plano.

Registrar o resultado num documento novo em `docs/proofs/` (nome a definir pelo responsável), ao lado das provas do KMS e da Jupiter, e citá-lo na aprovação da mainnet (`cd-config.md`, seção 6, etapa 3). Repetir o ensaio a cada 6 meses ou depois de qualquer mudança no esquema de recuperação.

## 8. Configuração do AWS Backup (referência; não alterada)

Já está no Terraform, adicionada pelo Environment Provisioning. Este estágio só aponta para ela.

| Item | Valor | Onde |
|---|---|---|
| PITR (RPO de 5 min) | 7 dias, nos dois bancos | `infra/terraform/modules/data/main.tf`, `aws_db_instance.main` (`backup_retention_period = 7`) |
| Cofre | `${name}-rds` (chave gerenciada do AWS Backup) | `infra/terraform/modules/data/backup.tf`, `aws_backup_vault.rds` |
| Plano | `${name}-rds-daily`, regra `daily-30-days`, `cron(0 5 * * ? *)` (05:00 UTC), janela de início 60 min, de conclusão 240 min | `aws_backup_plan.rds_daily` |
| Guarda | 30 dias (`daily_backup_retention_days`) | `backup.tf` |
| Seleção | as duas instâncias (`app` e `txsigner`) | `aws_backup_selection.rds` |
| Papel | `${name}-rds-backup`, com as políticas gerenciadas de backup e de restauração | `aws_iam_role.backup` |
| Conferência | H7 (PITR em dia) e H17 (ponto diário `COMPLETED`) | `validation-report.md`, seção 6 |
| Pendências | SP-15 (Vault Lock ou política que negue `DeleteRecoveryPoint`; antes da mainnet) e SP-19 (`aws:SourceAccount` na confiança do papel) | `security-posture-review.md`; `validation-report.md`, seção 11 |

## 9. Por que SSM Automation e AWS Incident Manager ficaram para depois (Q3)

| Ferramenta | Por que não agora | Quando rever |
|---|---|---|
| **SSM Automation** (runbooks executáveis) | Não há conta AWS para testar os documentos. Um documento não testado, ligado a um alarme, é mais arriscado que um passo manual escrito. E as ações mais importantes (kill switch, compensação, confirmação de restauração) são de propósito humanas e passam pela administração, não pela AWS. | Depois que o ensaio da devnet (seção 7 e `rollback-runbook.md`, seção 8) rodar os runbooks pelo menos uma vez sem correção. Candidatos, só de leitura ou reversíveis: (1) coleta de diagnóstico (`alarms`, serviços, eventos, consultas salvas) num relatório; (2) `disable-alarm-actions`/`enable-alarm-actions` com prazo, para rollbacks; (3) `force-new-deployment` do `api-worker`. Nunca: kill switch, compensação, restauração, troca do TxSigner, IAM ou KMS. |
| **AWS Incident Manager** (planos de resposta, contatos, escalonamento, acionamento por telefone) | Há um único plantonista, então não há escala nem rodízio para gerenciar. Os contatos externos ainda estão "a definir". O SNS com e-mail e SMS cobre o aviso. | Antes dos convidados, junto com o segundo contato (`escalation-matrix.md`, seção 5), ou se o fundador deixar de reconhecer um P1 em 15 min. Na hora, confirmar se o serviço ainda aceita contas novas e o custo, e comparar com um serviço de plantão pago (Q2: nenhum no piloto). |

## Sources

- `incident-response-questions.md` (Q1 a Q5, resumo confirmado).
- `runbooks.md` e `escalation-matrix.md` (este estágio).
- `operation/observability-setup/alarms.md` (tópico, severidades, `ok_actions`), `slo-config.md` (orçamento de erro e política), `log-queries.md` e `dashboards.md`.
- `operation/deployment-pipeline/rollback-runbook.md` (passos, ensaio e escalonamento), `cd-config.md` (4.6, 5, 6 e 7) e `deployment-strategy.md`.
- `operation/environment-provisioning/validation-report.md` (H1 a H22, seções 9 a 11) e `security-posture-review.md` (SP-13, SP-14, SP-15, SP-19).
- `construction/u1-bot-wallet/nfr-design/reliability-design.md` (RPO, RTO, ensaio, saque) e `security-design.md` (modo de recuperação, etiquetas imutáveis); `reliability-design.md` e `security-design.md` das U2 a U7 (falha fechada, pausas, idempotência, saque fora da barreira); `infrastructure-design/infrastructure-specification.md` de cada Unit (implantação e rollback).
- `construction/u5-real-operations/code-generation/code-summary.md` (kill switch, restauração, divergência, pausas globais).
- `docs/runbooks/txsigner-recovery.md` e `real-operations.md`.
- `infra/terraform/modules/data/main.tf` e `backup.tf` (PITR e AWS Backup).
- `team.md` (Deployment, Testing Posture) e `project.md` (Mandated e Forbidden); regras da fase Operation (Incident Response).
- `.claude/knowledge/aidlc-operations-agent/incident-response-guide.md` (estrutura da revisão pós-incidente, RTO e RPO).
