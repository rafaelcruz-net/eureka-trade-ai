# Projeto de segurança e isolamento — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-requirements/security-requirements.md` (U5-NFR1.1 a U5-NFR8.2), `nfr-design-questions.md` (Q4, Q5) e o código da trilha (0005, 0016, `AuditOutboxRelay`).

| Requisito | Como o projeto atende |
|---|---|
| U5-NFR6.1, U5-NFR6.2 | `RealAdminController` na entrada administrativa (só `ADMIN`); `IdempotencyGuard` comum; motivo de 1 a 500 caracteres; evento com o administrador e o usuário afetado. |
| U5-NFR6.3 | `WalletDisplay.abbreviate` única, com teste; o modelo não tem campo de chave. |
| U5-NFR1.1 | Ver "CI" abaixo. |
| U5-NFR1.2 | Regra ArchUnit: o código de operação de `botwallet` e `administration` não chama `signSwap` nem `signWithdrawal`; os comandos `LOWER_CAP` e `DEACTIVATE` levam só ids e valores. |
| U5-NFR1.3 | Ver "Redação da trilha" abaixo; o teste `NoWalletAddressAfterAnonymizationIT` percorre o esquema pelo `information_schema`. |
| U5-NFR8.1 | Regra ArchUnit: rede só em `botwallet.infra.github` e no RPC que já existe. |
| U5-NFR8.2 | A conferência de subida fica em `RealProperties` (API e worker), já que não existe `RealNetworkGuard`. Com `MEMETRADE_REAL_NETWORK=MAINNET`, ela exige `memetrade.real.founder-user-id` e recusa o `ConfiguredCiStatus`. |
| U5-NFR2.1 | `ReconciliationPolicy` só recebe a M2 REAL; ArchUnit sem PAPER. |

## CI (U5-NFR1.1, Q5)

**SHA da imagem.**
- Os Dockerfiles da API e do TxSigner ganham `ARG BUILD_SHA` e `ENV MEMETRADE_BUILD_SHA=$BUILD_SHA`.
- A CI e a implantação passam `--build-arg BUILD_SHA=<sha>`. A linha do `ci.yml` vai como texto para o responsável.
- O Spring lê `memetrade.build.sha`.
- Sem SHA, o portão fica `CI_UNREACHABLE`, e a mainnet não sobe.

**Consulta.**
- A chamada é `GET /repos/{owner}/{repo}/actions/workflows/ci.yml/runs?head_sha=<sha>&branch=main&event=push`, de onde só se leem `status` e `conclusion`.
- Basta uma execução concluída com `success` para o portão ficar verde.
- As execuções `cancelled`, deixadas pelo `cancel-in-progress`, são ignoradas.
- Sem nenhuma execução com `success`:
  - com alguma execução em andamento, o portão fica `CI_RED` e é conferido de novo;
  - com todas concluídas sem `success`, fica `CI_RED`.
- Erro de rede ou HTTP dá `CI_UNREACHABLE`.

**Token.**
- É um token fino do GitHub, só para este repositório, com a permissão "Actions: read" e a "Metadata: read" obrigatória.
- Vale 90 dias e é trocado pelo runbook.
- Entra pela variável `MEMETRADE_GITHUB_ACTIONS_TOKEN`, só no worker.
- O logger do `RestClient` mascara `Authorization`.

## Redação da trilha (U5-NFR1.3, Q4 revista)

O gatilho `audit_event_reject_change`, da 0005, recusa todo UPDATE, até do dono. Por isso a 0021 o substitui por uma versão que **continua recusando todo UPDATE**, com uma única exceção. Para ela valer, as três condições abaixo precisam ser verdadeiras ao mesmo tempo:
1. A variável local `memetrade.audit_redaction` vale `on`. Só a função de redação a liga, com `set_config(..., true)`, que dura só a transação.
2. A linha nova é igual à antiga, exceto por `bot_wallet_address` posto em NULL e pela chave `real.botWalletAddress` tirada do `payload`.
3. A linha antiga tinha um desses dois valores.

**Privilégios.** O papel da aplicação continua sem UPDATE em `audit_event`, então só a função, que roda como dona, consegue gravar. Mesmo que a aplicação ligasse a variável, ela não teria o privilégio.

**Função `redact_real_wallet_audit(user_id)`.**
- É `SECURITY DEFINER`, pertence à dona do esquema, tem `search_path` fixo e `EXECUTE` só para o papel da aplicação.
- Primeiro pega `FOR UPDATE` nas linhas de `audit_outbox` do usuário, inclusive as já entregues, que ficam 7 dias. Com isso, espera qualquer entrega do `AuditOutboxRelay` em andamento.
- Depois tira `real.botWalletAddress` de `audit_outbox.payload` e redige as linhas de `audit_event` do usuário.
- Uma entrega posterior lê o `payload` já redigido. Não há corrida com o relay, porque o relay também trava a linha com `FOR UPDATE SKIP LOCKED`.
- Devolve a contagem e grava o evento de redação.

**Chave de seleção (ajuste da iteração 2).** Nem `audit_event` nem `audit_outbox` têm coluna de usuário. Por isso a função recebe o endereço da carteira do bot que está sendo encerrada, que é conhecido na anonimização, e seleciona:
- em `audit_event`, pela coluna tipada `bot_wallet_address = <endereço>`, com índice parcial novo na 0021 (`WHERE bot_wallet_address IS NOT NULL`);
- em `audit_outbox`, pela expressão `payload->'real'->>'botWalletAddress' = <endereço>`, com índice parcial de expressão na 0021.

**Condição 2 por tabela.** Em `audit_event`, só `bot_wallet_address` pode virar NULL; a tabela não tem `payload`. Em `audit_outbox`, só a chave `real.botWalletAddress` sai do `payload`.

**Ordem.** A redação roda no fim da transação de anonimização, depois dos eventos de encerramento que ela mesma grava. Assim, um evento recém-gravado no outbox também sai redigido.

**Teste.** A redação funciona, e um UPDATE de qualquer outra coluna, mesmo com a variável ligada, é recusado.
