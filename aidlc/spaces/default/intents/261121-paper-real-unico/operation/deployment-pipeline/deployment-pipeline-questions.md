# Perguntas do Deployment Pipeline — intent `261121-paper-real-unico`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `team.md` (Deployment), `project.md`.
- `261113-trading-real-bot/operation/deployment-pipeline/` (`cd-config.md`, `deployment-strategy.md`, `rollback-runbook.md`).
- `construction/ci-pipeline/` (`ci-config.md`, `quality-gates.md`).
- `verification/phase-check-construction.md`, seções 7 e 8: as 5 condições da transição.
- Os `infrastructure-design/` das Units, em especial a F4 da U4 (as variáveis da flag por ambiente no `tfvars`) e o D-INF2 da U2 (o teto de conexões do banco no rolling).

## Q1. Estratégia de implantação

Qual estratégia de implantação o intent usa?

A. A do `team.md` e do 261113, sem mudança: rolling no ECS (a tarefa nova saudável antes de a antiga sair), circuit breaker com volta automática, TxSigner primeiro, smoke e 10 minutos de observação. O intent não acrescenta serviço nem tarefa nova. (recomendado)
B. Blue/green ou canary
X. Other (please specify)

[Answer]: A

## Q2. Promoção entre ambientes

Como um mesmo SHA passa de um ambiente para o outro?

A. É o fluxo do `team.md`:
   - um SHA de `main` com `ci` e `security` verdes passa primeiro pela devnet e só depois vai para a mainnet;
   - a implantação é manual, pelo `deploy.yml` (`workflow_dispatch`), ou pelo caminho manual do fundador com MFA enquanto o `deploy.yml` não existe;
   - a mainnet exige a aprovação do fundador no Environment `mainnet`.

   Entram as condições da transição:
   - a mainnet fica bloqueada até a `performance-validation` registrar a carga `pnl-by-mode` verde e a medição na devnet;
   - só um run verde vale, e uma repetição do `e2e` fica registrada (TGT-E2E-FLAKY).

   (recomendado)
B. Outro fluxo
X. Other (please specify)

[Answer]: A

## Q3. Migrações no deploy

Como as migrações deste intent entram na implantação?

A. Rodam pelo `db-migrate` antes das imagens, na ordem: `0023` (`real_entry_slot` e o índice `CONCURRENTLY`), `0024`, `0025`, `0026` (dois changesets com `lock_timeout` de 5 s) e `0027` (o índice `CONCURRENTLY`).
   - Todas são só de expansão e seguras com a imagem anterior.
   - O rollback volta só as imagens; nenhuma migração é revertida em produção.
   - Uma falha por `lock_timeout` (`55P03`) para a implantação. Para repetir, segue o runbook: conferir o `pg_stat_activity` e reimplantar o mesmo SHA.
   - Antes do primeiro rolling na nuvem, vale o teto de conexões do D-INF2.

   (recomendado)
B. Migrações em implantação separada
X. Other (please specify)

[Answer]: A

## Q4. Flags

Como as flags deste intent são tratadas?

A. A flag `memetrade.real.own-quantity-sell` da U4 segue a F4:
   - duas variáveis Terraform, `real_own_quantity_sell_api` e `real_own_quantity_sell_worker`, com padrão `false` e a validação de que o worker não liga sem a API;
   - o valor de cada ambiente fica no `tfvars` dele, fora do repositório;
   - para virar: primeiro a API, depois o worker, sem nenhuma SELL pendente nem em andamento;
   - o rollback desliga primeiro o worker e depois a API.

   **A flag continua desligada em todos os ambientes** até cumprir a condição 1 da transição: a medição por passo, a decisão F7 e a medição oficial dentro da meta, além da Performance Validation do 261113. As flags do REAL (`memetrade.real.enabled`, `txsigner.real.enabled`), o kill switch e a liberação seguem o 261113, sem mudança. O `team.md` diz que uma flag muda por commit; essa diferença com a F4 fica para o ritual de aprendizado. (recomendado)
B. Flag no AppConfig
X. Other (please specify)

[Answer]: A

## Q5. Rollback

Como se volta atrás numa implantação deste intent?

A. Vale o `rollback-runbook.md` do 261113, mais o que este intent acrescenta:
   - imagem anterior pelo SHA, com as migrações mantidas;
   - desligar a flag da U4 na ordem worker → API, quando ela estiver ligada;
   - as telas de P&L por modo (U1 e U3) não têm flag: o rollback delas é só pela imagem;
   - o saque continua disponível durante todo o rollback;
   - uma implantação só termina com o smoke verde.

   (recomendado)
B. Outro
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Estratégia:** rolling no ECS, com circuit breaker, TxSigner primeiro, smoke e 10 minutos de observação. Nenhum serviço novo [Q1].
- **Promoção:** o mesmo SHA verde passa pela devnet e depois pela mainnet, com a sua aprovação. A mainnet fica bloqueada até a carga e a devnet ficarem verdes, e só um run verde vale [Q2].
- **Migrações:** da 0023 à 0027, só de expansão e antes das imagens. O rollback volta só as imagens, e há um passo para repetir quando estourar o `lock_timeout` [Q3].
- **Flag da U4:** duas variáveis por ambiente no `tfvars`, desligadas até a decisão F7 e a medição oficial. Liga primeiro a API e depois o worker; desliga na ordem inversa [Q4].
- **Rollback:** o runbook do 261113, mais a flag da U4 e as telas de P&L. O saque fica sempre disponível [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
