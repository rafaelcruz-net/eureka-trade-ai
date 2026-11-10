# L2 e L3 — Novo aceite dos termos (U3 do intent `261109-termos-lgpd`)

Contrato da U3 `u3-reacceptance` (histórias US2.3, US4.1 e US4.2). Quando uma versão nova dos termos passa a valer, o convidado que ainda não a aceitou só usa as rotas liberadas até aceitar. A especificação gerada do código fica em [`apps/api/openapi/openapi-guest.yaml`](../../apps/api/openapi/openapi-guest.yaml); o esquema comum `Problem` também mudou em [`openapi-admin.yaml`](../../apps/api/openapi/openapi-admin.yaml). Os blocos L2 e L3 de `contract-summary.md` foram atualizados junto com o código.

## Rota de aceite (L2)

`POST /api/v1/terms/acceptances` com `Idempotency-Key` (UUID) e o corpo `{"version": "<versão>"}`.

| Situação | Resposta |
|---|---|
| A versão é a vigente efetiva e a pessoa ainda não a aceitou | `200` com `TermsStatus` em dia; grava um aceite `REACCEPTANCE` (sem convite) com o instante do relógio e publica `TERMS_ACCEPTED` |
| A pessoa já aceitou essa versão (outra chave) | `200` com o `TermsStatus` existente, sem gravar nem publicar |
| Mesma chave e mesma versão | a mesma resposta guardada (mecanismo comum de idempotência do Identity) |
| Mesma chave com outra versão | `409 IDEMPOTENCY_CONFLICT` |
| A versão não é a vigente efetiva (superada, futura ou inexistente) | `409 TERMS_VERSION_OUTDATED` com `currentVersion` no problema; nada é gravado e a chave não fica consumida |
| Versão ausente, vazia ou com mais de 32 caracteres, campo desconhecido (por exemplo `tenantId`) ou chave ausente | `400 VALIDATION_FAILED` |
| Sem sessão | `401 UNAUTHENTICATED` |
| Token de administrador | `403 FORBIDDEN_ROLE` (regra de rotas do administrador) |

- **Pessoa e tenant:** vêm só do token. O corpo aceita só `version`.
- **Corrida:** dois aceites simultâneos da mesma versão gravam uma linha só (`INSERT ... ON CONFLICT DO NOTHING` na restrição única `uq_terms_acceptance_user_version`). O segundo lê o aceite gravado, e só a inserção efetiva publica o evento.
- **Auditoria:** `TERMS_ACCEPTED`, ator a pessoa, alvo `terms:<versão>`, `details` `{version, source}`. Nunca leva a carteira.
- **Log:** uma linha `INFO Terms accepted: version=<v>, source=REACCEPTANCE`, sem pessoa, tenant nem carteira.
- **Respostas** levam `Cache-Control: no-store`.

### `TermsStatus`

| Campo | Significado |
|---|---|
| `acceptedVersion`, `acceptedAt` | o aceite mais recente da pessoa (UTC) |
| `currentVersion` | a versão vigente efetiva (BR1.16 da U1) |
| `upToDate` | verdadeiro quando existe um aceite da versão vigente, a mesma decisão da trava |

`GET /api/v1/account` ganhou o campo aditivo `terms: TermsStatus`. Ele fica ausente só numa conta sem nenhum aceite, o que hoje só acontece em contas semeadas por teste.

## Trava de novo aceite (L3)

O filtro `TermsReacceptanceFilter` (`FilterOrder.TERMS_REACCEPTANCE`, logo depois da autenticação e da regra de rotas do administrador) roda só no processo `api`.

1. Só age em token de convidado (`BETA_USER`). Token de administrador e requisição sem sessão seguem para as próprias regras.
2. Caminho sob um prefixo liberado segue. O prefixo vale para o caminho inteiro ou para o que está abaixo dele, nunca para um caminho que só começa com o mesmo texto (`/api/v1/accounting` é travado). A decisão usa o caminho normalizado, como as demais regras de rota.
3. Nas demais rotas, uma consulta `EXISTS` pela pessoa, pelo tenant e pela versão vigente efetiva, pelo índice da restrição única. Não há cache: o aceite vale na requisição seguinte, em qualquer instância, com o mesmo token.
4. Sem aceite da vigente, inclusive numa conta sem nenhum aceite: `403 TERMS_REACCEPTANCE_REQUIRED` com `currentVersion`. A trava não publica `REQUEST_FORBIDDEN` nem escreve log por bloqueio; ela só conta (`memetrade.terms.reacceptance.blocked`).
5. Banco indisponível: `503 SERVICE_UNAVAILABLE`, escrito pela própria trava, e a requisição nunca segue sem a conferência. O log traz só a classe da exceção.

A trava compara com todos os aceites, não só com o mais recente: se a vigente voltar a uma versão já aceita, a pessoa segue em dia.

### Rotas liberadas

Configuradas em `memetrade.terms.reacceptance.allowed-prefixes` (`application.yml`, nunca variável de ambiente). Lista vazia ou prefixo fora de `/api/v1/` impedem a subida.

| Prefixo | Por quê |
|---|---|
| `/api/v1/legal/` | ler os documentos |
| `/api/v1/terms/acceptances` | aceitar |
| `/api/v1/account` | ver a Conta e a situação dos termos |
| `/api/v1/auth/logout` | sair |
| `/api/v1/auth/refresh` | renovar a sessão |

A U4 acrescenta `/api/v1/privacy/` (pedidos de titular). As rotas de bot ficam travadas. O worker não tem camada web, então os bots PAPER ativos continuam no ciclo durante a trava.

O teste obrigatório do time, `TermsReacceptanceRoutesIT`, percorre todas as rotas do convidado do `route-registry.yml` com uma pessoa desatualizada e falha quando uma rota fora da lista deixa passar ou quando uma rota liberada é travada.

## App do convidado

- **Redirecionamento:** o `ApiProvider` do layout registra no cliente da API um único tratador do `403 TERMS_REACCEPTANCE_REQUIRED`. As chamadas REST (com ou sem renovação do token) e o canal ao vivo do painel usam esse tratador, que leva a `/novo-aceite?voltar=<tela atual>` com `router.replace`.
- **`?voltar=`:** só aceita caminho interno do app (começa com `/`, sem `//`, sem `\` e sem espaço). Qualquer outro valor volta ao painel `/`.
- **`/novo-aceite`:** fora do `AppShell` com navegação, com o rodapé. Mostra o resumo dos termos e da política da vigente (lido no servidor pelo mesmo cliente de L1 de `/termos`, com cache de 300 s), os links completos em outra aba, as duas caixas e "Aceitar e continuar", desabilitado com o motivo em texto até as duas caixas estarem marcadas. Um `409` recarrega os documentos direto da API, desmarca as caixas e explica que a versão mudou.
- **Convite:** mostra o mesmo resumo e os links. As caixas ficam desabilitadas enquanto os documentos carregam ou depois de uma falha, com o estado em texto e "Tentar de novo".
- **Conta:** o painel "Situação dos termos" mostra a versão aceita e a data e, fora de dia, o link "Aceitar a nova versão".
- **Acessibilidade:** só teclado, com a ordem título, resumo, links, caixas, botão; foco visível; rótulos visíveis; estados numa região `aria-live="polite"`.

## Publicar uma versão nova dos termos

Uma versão nova é uma implantação com uma pasta nova no catálogo (veja "Catálogo e ativação" e o checklist de [`u1-legal-documents.md`](u1-legal-documents.md)). **Assim que a versão nova passa a ser a vigente efetiva, a trava vale para todos os convidados**: cada um só volta a usar as rotas travadas depois de aceitar.

1. Prepare a pasta `terms/<versão>/` revisada (`legalReviewedAt`, `legalReviewedBy`) com `effectiveAt` na data da publicação (UTC) e marque-a como `current=true`.
2. Avise os convidados antes, se possível: durante a trava eles ficam sem o scanner, o painel, o P&L e as telas de bot. Os bots PAPER ativos continuam rodando.
3. Implante e confira no componente de saúde `legalDocuments` que `currentVersion` é a versão nova.
4. Acompanhe `memetrade_terms_reacceptance_blocked_total` (pico logo depois da publicação) e `memetrade_terms_acceptances_total{source="REACCEPTANCE"}` (adesão). O p95 de `memetrade_terms_reacceptance_check_seconds` deve ficar em até 5 ms (alerta `TERMS_CHECK_SLOW`).
5. **Rollback:** voltar a imagem anterior. A migração `0013` não é desfeita depois do primeiro aceite `REACCEPTANCE`. Se a vigente voltar a uma versão que as pessoas já aceitaram, elas seguem em dia sem novo aceite.

## Dados de teste

- O `SmokeAccountSeeder` (perfil `test`) grava para a conta do smoke um aceite `REACCEPTANCE` da vigente efetiva, só quando falta, fora do retorno antecipado da criação da conta. Os specs existentes continuam passando pela trava.
- Os testes do backend semeiam contas com o aceite da vigente do catálogo padrão (`TestAccounts.CURRENT_TERMS_VERSION`, hoje `v1`); `TestAccounts.betaUserWithoutAcceptance` cria uma conta sem aceite.
- O spec `apps/web/e2e/reacceptance/smoke.spec.ts` insere, pelo `DATABASE_URL` do `verify.sh`, uma conta com aceite só da `v1` do catálogo de teste (vigente `v2`), entra, é levado ao novo aceite, aceita pelo teclado e volta. O responsável do projeto acrescenta `e2e/reacceptance` ao fim da lista de specs do `scripts/verify.sh`.

## Carga

`LOAD_SCENARIO=reacceptance scripts/load-test.sh` (perfis `test,loadtest`) cria 20 pessoas desatualizadas pelo mesmo caminho SQL do spec. Para cada uma: entra, recebe o `403` numa rota travada, aceita e recebe `200` na mesma rota. O cenário reprova com p95 do aceite acima de 500 ms ou da trava acima de 5 ms, lidos dos histogramas de `/actuator/prometheus`, ou quando alguma pessoa não completa o caminho. O relatório fica em `docs/perf/u3-reacceptance-<data>.md`. A opção `reacceptance` do workflow `load-test.yml` é acrescentada pelo responsável do projeto.
