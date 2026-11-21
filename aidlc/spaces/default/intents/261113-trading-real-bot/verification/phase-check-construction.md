# Verificação de fase — Construction → Operation (Trading REAL)

**Veredito: o portão entre Units NÃO passou (`pass: false`, 128 de 134 IDs).** As tabelas por Unit passam sem achado, as 7 Units estão construídas e testadas, e a CI impõe os comandos do Build and Test. Os 6 IDs que faltam são todos adiamentos com motivo e dono, aceitos pelo responsável no portão do Build and Test ("Accept failure", 2026-11-20). **Recomendação: transição condicional**, que só vale se o responsável confirmar as condições da seção 6 neste portão. Sem essa confirmação, a regra do estágio manda parar a transição e voltar à Code Generation da U7 (seção 5).

Conversa: português (Brasil). Data: 2026-11-20. Commit verificado: `3497b2bc49052f01adb93de7094a05880532222b`. Fontes: `construction/build-and-test/cross-unit-traceability.md`, os 7 `construction/<unit>/code-generation/traceability.json`, `construction/build-and-test/build-and-test-summary.md` e `test-results.md`, e `construction/ci-pipeline/quality-gates.md`.

## 1. Rastreabilidade da Code Generation por Unit

Comando, nesta execução, para cada Unit, sobre uma cópia da tabela do registro:
`aidlc engine sensor-traceability --stage code-generation --output-path C:/Users/rafae/.claude/jobs/ce4ac752/tmp/construction/<unit>/code-generation/traceability.json`

| Unit | Arquivo presente | Linhas | OK | Deferred | N/A | `pass` | Achados (gaps, orphans, ausentes, inválidos) |
|---|---|---|---|---|---|---|---|
| `u1-bot-wallet` | sim | 117 | 112 | 3 | 2 | true | 0 |
| `u2-real-market-data` | sim | 64 | 60 | 2 | 2 | true | 0 |
| `u3-real-risk` | sim | 80 | 76 | 1 | 3 | true | 0 |
| `u4-real-execution` | sim | 95 | 93 | 1 | 1 | true | 0 |
| `u5-real-operations` | sim | 82 | 80 | 0 | 2 | true | 0 |
| `u6-real-modes-ui` | sim | 108 | 108 | 0 | 0 | true | 0 |
| `u7-exits-and-fiscal` | sim | 117 | 103 | 14 | 0 | true | 0 |
| **Total** | **7 de 7** | **663** | **632** | **21** | **10** | **7 de 7** | **0** |

Nenhuma tabela tem `GAP` nem `ORPHAN`, e nenhuma tem linha `reverse`. Os 10 `N/A` são critérios de outra Unit pelo mapa de histórias (por exemplo, AC1.1.3 na U1, que é da U6), cada um com a Unit dona como justificativa. Os 21 `Deferred` são estes, todos com motivo e dono:

| Unit | IDs adiados | Dono |
|---|---|---|
| U1 | NFR3.9 (carga REAL informativa), NFR4.1 (SLO de 99,5% em 30 dias), NFR9.1 (custo do KMS na fatura) | `performance-validation` (NFR3.9, NFR4.1); `environment-provisioning` (NFR9.1) |
| U2 | NFR3.4 (sem piora na leitura do scanner), NFR9.1 (preço do plano da CoinGecko) | `performance-validation`; `environment-provisioning` |
| U3 | NFR5.11 (alarme de p95 na nuvem) | `observability-setup` |
| U4 | NFR5.13 (alarme de p95 na nuvem) | `observability-setup` |
| U7 | BR0.2, BR0.5, BR1.1, BR1.3, BR1.4, BR2.1 a BR2.5, BR9.2, NFR1.1, NFR1.3, NFR8.2 | Code Generation da U7, depois do `infra/solana-fixtures/record.sh --trigger` (portão BR0) |

**Resultado: todas as tabelas presentes, nenhum achado sem resolução nas tabelas da Code Generation.** `Deferred` com motivo e dono é um estado válido do formato (`verification.md`), não um achado do sensor.

## 2. Units construídas e testadas

| Unit | Construída | Testada (run `ci` 44765239890) | Cobertura |
|---|---|---|---|
| U1 `u1-bot-wallet` | sim | `backend`, `txsigner`, `frontend`, spec `real-bot-wallet` (4) | API 92,34% (botwallet 88,0%); TxSigner ≥ 80%; web 94,68% |
| U2 `u2-real-market-data` | sim | `backend`, `frontend` | marketdata 93,2% |
| U3 `u3-real-risk` | sim | `backend` | riskcontrol 93,0% |
| U4 `u4-real-execution` | sim | `backend`, `txsigner`, spec `real-swap` (2) | realexecution 90,3% |
| U5 `u5-real-operations` | sim | `backend`, `admin`, spec `real-ops` (4) | administration 88,5%; admin 89,81% |
| U6 `u6-real-modes-ui` | sim | `backend`, `frontend`, `admin`, spec `real-ui` (6) | web 94,68%, admin 89,81% |
| U7 `u7-exits-and-fiscal` | sim, com o portão BR0 aberto | `backend`, `txsigner`, `frontend`, specs `real-v1-exports` (3) e `real-v2-exits` (4) | realexits 90,5% |

Totais: 4.795 testes distintos, 0 falhas, 0 pulados; 42/42 specs nos jobs `smoke` e `e2e`. A matriz do B&T tem 738 `Met`, 0 `Not Met`, 54 `Unverified` (todos com dono) e 1 `N/A` (`BT-CI-BEFORE-MERGE`, atendido por este estágio, `quality-gates.md` seção 2).

**Resultado: as 7 Units estão construídas e testadas.** A U7 está construída no que não depende da gravação real da Trigger. As saídas condicionais na Jupiter real ficam desligadas por padrão (`memetrade.real.exits.enabled=false`), e o TxSigner recusa `TRIGGER_CREATE` e `TRIGGER_CANCEL` com `POLICY_NOT_RECORDED` (testado).

## 3. Portão entre Units (FR, NFR e AC)

Comando: `aidlc engine sensor-traceability --stage build-and-test`, executado de novo nesta verificação. Resultado igual ao do Step 10 do B&T: `pass: false`, 134 IDs avaliados, 6 não cobertos.

| Medida | Valor |
|---|---|
| FR | 48 de 48 cobertos |
| AC | 76 de 76 cobertos (a matriz ainda julga AC3.4.1, AC3.4.2 e AC8.1.3 `Unverified` pelo portão BR0) |
| NFR de requisito | 4 de 10 cobertos |
| **Não cobertos** | **NFR1, NFR3, NFR4, NFR5, NFR8, NFR9** |

| ID | Partes não construídas | Dono | Fase do dono |
|---|---|---|---|
| NFR1 | U7 NFR1.1 e NFR1.3 (portão BR0) | Code Generation da U7, depois do `record.sh --trigger` | **Construction** |
| NFR8 | U7 NFR8.2 (portão BR0) | Code Generation da U7, depois do `record.sh --trigger` | **Construction** |
| NFR3 | U1 NFR3.9; U2 NFR3.4 | `performance-validation` | Operation |
| NFR4 | U1 NFR4.1 | `performance-validation` | Operation |
| NFR5 | U3 NFR5.11; U4 NFR5.13 | `observability-setup` | Operation |
| NFR9 | U1 NFR9.1; U2 NFR9.1 | `environment-provisioning` | Operation |

Nenhum ID ficou sem cobertura por falta de código ou por erro de formato: cada NFR não coberto tem todas as outras partes `OK` nas Units donas (`cross-unit-traceability.md`, "Cobertura por ID").

**Resultado: o portão entre Units não passou.**

## 4. A CI impõe os comandos do Build and Test

Todo comando que a matriz do B&T marca `Met` roda num job da CI em todo `pull_request` e em todo `push` em `main` (`ci-config.md` seção 2; `quality-gates.md` G1 a G20). Os pisos de 80% são medidos e impostos separadamente na API, no TxSigner, no web e no admin. Os 42 specs rodam no `smoke` e de novo no `e2e`. O único comando sem job é o teste de carga do REAL, que é `Unverified` com dono `performance-validation` e, pelo `team.md`, não é portão.

Ressalva: `main` não tem proteção. Nos pushes diretos previstos no `team.md`, a CI roda logo depois do commit, e o bloqueio é por disciplina (`quality-gates.md` seção 2).

**Resultado: atendido.**

## 5. Os 6 IDs são achados sem resolução ou adiamentos aceitos?

A regra do estágio diz: "If any traceability file is missing or any unresolved finding remains, stop the Construction → Operation transition and revisit the owning stage."

**NFR3, NFR4, NFR5 e NFR9 são adiamentos, não achados sem resolução.**
- Cada parte que falta tem `Deferred` com motivo e dono na tabela da Unit, e o sensor por Unit aceita essas linhas (0 achados).
- Os donos são estágios da **Operation**: teste de carga, SLO medido com tráfego real, alarmes na nuvem e faturas da conta AWS. Nenhum deles pode ser feito dentro da Construction. Parar a transição por eles não resolveria nada e impediria justamente os estágios que os fecham.
- O responsável aceitou esses adiamentos no portão do B&T ("Accept failure", 2026-11-20, `test-results.md` seção 7).

**NFR1 e NFR8 são o caso que mais se aproxima da regra.**
- O dono é a Code Generation da U7, um estágio da **Construction**. Pela letra da regra, é exatamente "revisit the owning stage".
- Mas essa volta não pode ser feita agora. Ela depende antes da gravação real da Trigger (`record.sh --trigger`), uma ação do responsável com rede e carteira real que exige aprovação explícita (`project.md` Forbidden: "NEVER movimentar fundos reais … sem aprovação explícita do responsável do projeto").
- O risco está contido no código: as saídas condicionais ficam desligadas por padrão, e o TxSigner recusa os pedidos de Trigger com `POLICY_NOT_RECORDED`.
- O responsável também aceitou esses adiamentos no portão do B&T, já sabendo que o dono era a U7.

**Leitura honesta:** os 6 não são achados esquecidos nem lacunas sem dono. São adiamentos rastreados e aceitos. Mesmo assim, o portão automático falhou, e NFR1 e NFR8 continuam pendentes **dentro da Construction**. Este documento não os declara resolvidos e não chama o resultado de PASS.

## 6. Recomendação (a decisão é do responsável)

**Recomendo seguir para a Operation de forma condicional**, porque 4 dos 6 IDs só se fecham lá e os outros 2 dependem de uma ação do responsável que nenhum estágio pode antecipar. As condições abaixo valem até cada item ser verificado:

1. **U7 (NFR1, NFR8 e o portão BR0):** `memetrade.real.exits.enabled` fica `false` em qualquer ambiente com fundos reais até que o responsável rode o `record.sh --trigger`, a Code Generation da U7 seja reaberta (TriggerPolicy e testes de ataque S21–S26 e S40–S42 escritos antes do código) e o sensor entre Units dê NFR1 e NFR8 cobertos.
2. **NFR3, NFR4, NFR5, NFR9:** a implantação do REAL na mainnet fica bloqueada até `performance-validation`, `observability-setup` e `environment-provisioning` verificarem as partes adiadas, como o `team.md` (Rollout do REAL) e o B&T ("Pronto para implantar: não") já exigem.
3. **As 54 metas `Unverified` da matriz do B&T** continuam com os donos registrados e voltam a ser conferidas antes da mainnet.

**Se o responsável preferir a leitura literal da regra** (NFR1 e NFR8 como achados sem resolução da Construction), a transição **para aqui**, e o próximo passo é a volta à Code Generation da U7 depois do `record.sh --trigger`. Nesse caso, a Operation espera a gravação.

## 7. Aprovação humana

- [ ] O responsável aceita a transição condicional da seção 6, com as condições 1 a 3. Se não aceitar, a transição para, e o próximo passo é a Code Generation da U7 depois do `record.sh --trigger`.

O registro do `PHASE_VERIFIED` na trilha de auditoria é feito pelo motor, não por este documento.
