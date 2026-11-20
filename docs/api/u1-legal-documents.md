# L1 — Documentos legais públicos (U1 do intent `261109-termos-lgpd`)

Contrato da U1 `u1-legal-documents` (histórias US1.1 a US1.3, US2.1, US2.2 e US2.4 a US2.6). As rotas são públicas, só de leitura, e servem os termos de uso, a política de privacidade e o aviso de risco do catálogo versionado. A especificação gerada do código fica em [`apps/api/openapi/openapi-guest.yaml`](../../apps/api/openapi/openapi-guest.yaml) e também em [`openapi-admin.yaml`](../../apps/api/openapi/openapi-admin.yaml), porque o app do administrador lê as mesmas rotas. O bloco L1 de `contract-summary.md` foi atualizado junto com o código.

## Rotas

| Rota | Resposta | Regras |
|---|---|---|
| `GET /api/v1/legal/current` | `LegalDocuments` da versão vigente efetiva | BR1.1, BR1.7, BR1.8 |
| `GET /api/v1/legal/versions` | `LegalVersionRef[]`: vigente e anteriores, mais nova primeiro; legacy com `provisional: true` | BR1.9 |
| `GET /api/v1/legal/versions/{version}` | `LegalDocuments` de uma versão do histórico; fora dele, `404 NOT_FOUND` | BR1.10 |

- **Sem sessão:** `GET` e `HEAD` não pedem token. Outro método sem sessão recebe `401 UNAUTHENTICATED`, como as demais rotas; com sessão, `405`.
- **CORS:** as origens dos dois apps (`GUEST_APP_ORIGIN` e `ADMIN_APP_ORIGIN`), só `GET` e `HEAD`, sem credenciais, expondo só `ETag`.
- **Cache:** respostas `200` e `304` levam `Cache-Control: public, max-age=300` e um `ETag` forte (SHA-256 do corpo, montado na subida). `If-None-Match` igual responde `304` sem corpo. `404`, `429` e erros mantêm `no-store`.
- **Nulos explícitos:** `privacy` e os `summary` vão como `null` numa versão legacy; um campo de implantação ausente vai como `null` ("a definir"). Os campos nunca são omitidos.
- **Limite:** 60 requisições por minuto por IP do cliente (`memetrade.legal.rate-limit-per-minute`), com contadores próprios. Excedeu: `429 RATE_LIMITED` com `Retry-After`. A lista `MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS` isenta só o par direto da conexão (os contêineres `web` e `admin`), nunca o `X-Forwarded-For` nem o gateway da ponte. O perfil `loadtest` eleva o limite em `application-loadtest.yml`.
- **Saúde:** o componente `legalDocuments` fica sempre `UP`, com os detalhes `currentVersion` e `publishable`. Quando falta algum dos três campos de implantação, ele mostra `publishable=false`, e o alerta `LEGAL_NOT_PUBLISHABLE` lê esse detalhe. Ele não fica `DEGRADED` porque a ordem de status do actuator põe `DEGRADED` antes de `UP`, o que derrubaria a saúde geral (NFR7.3). Ele não entra nos grupos de liveness e readiness.

## Catálogo e ativação

Cada versão é uma pasta em `apps/api/src/main/resources/terms/<versão>/`:

| Arquivo | Conteúdo | Obrigatório |
|---|---|---|
| `version.properties` | `version`, `current`, `legacy`, `effectiveAt` (instante UTC), `legalReviewedAt` (data), `legalReviewedBy` | sempre (`effectiveAt` inclusive) |
| `terms.txt`, `risk-notice.txt` | texto simples; uma linha `## Título` abre uma seção | sempre |
| `privacy.txt` | política de privacidade, com as seções exigidas | versão não legacy |
| `terms-summary.txt`, `privacy-summary.txt` | 5 a 7 tópicos, um por linha | versão não legacy |
| `retention.tsv` | `dataItem`, `purpose`, `period`, `endAction`, separados por tabulação | versão não legacy |

Na subida, a API lê o catálogo uma vez. A versão marcada (`current=true`) é a vigente se for **ativável**:
- revisada (data e responsável da revisão jurídica) ou legacy;
- com `effectiveAt` não posterior ao relógio;
- publicável (três campos de implantação) quando `MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE=true`; a legacy é isenta disso.

Se a marcada não for ativável, vale a ativável de vigência mais recente **anterior** a ela, pela data e nunca pelo nome, e o log traz `WARN Legal version <v> ignored: <motivo>`. Sem nenhuma ativável, a API não sobe e diz o motivo. Arquivo acima de 200 KB, mais de 100 seções por versão, resumo fora de 5 a 7 tópicos ou arquivo obrigatório ausente também impedem a subida.

O aceite no convite só aceita a versão vigente efetiva, e a Conta só acha versões do histórico. Uma versão ignorada ou futura nunca é aceita nem exibida (BR1.16).

## Situação das versões

- **v1 (legacy):** textos provisórios do beta PAPER. Vigente até 2026-11-20 e mantida no histórico.
- **v2 (rascunho, nunca publicado):** redigida a partir do que o sistema fazia no beta PAPER. Foi **substituída pela v3** e ficou com `current=false`, sem `legalReviewedAt` nem `legalReviewedBy`. Ela nunca é ativada nem listada, e cada subida registra `Legal version v2 ignored: not legally reviewed`. A pasta continua porque uma versão nunca é apagada nem reescrita como outro texto.
- **v3 (vigente, intent `261113-trading-real-bot`, U6, etapa 19):** termos, aviso de risco e política de privacidade com o REAL. Os textos descrevem a carteira do bot com a chave no KMS, o teto, o saque só para a carteira principal, os três modos, a Jupiter, as taxas da rede, os limites e o kill switch. Eles trazem as três frases do AC1.1.3: perda de todo o valor depositado, responsabilidade compartilhada da carteira do bot e nenhuma promessa de lucro. A tabela de retenção mantém as 13 linhas da v2 e acrescenta 9 linhas dos dados do REAL (revisão 03, R-12): carteira do bot, ledger, ordens, pedidos de decisão, saídas condicionais, liberações, conferências, divergências e comandos ao TxSigner. Os prazos são os das purgas e dos gatilhos das migrações 0019, 0021 e 0022, e o ledger e os depósitos nunca são apagados. O `RetentionCatalog` tem as mesmas 22 linhas, e cada tabela das purgas do REAL declara o seu prazo (J7). O `RetentionConformanceTest` compara o catálogo com a versão vigente linha por linha e confere que a tabela da v2, nunca editada, é o começo do catálogo. O responsável do projeto autorizou a ativação em 2026-11-20, depois da autorização do advogado: `effectiveAt=2026-11-20T00:00:00Z`, `legalReviewedAt=2026-11-20` e `legalReviewedBy=Assessoria jurídica do projeto`. Esses campos são genéricos por escolha do responsável.

Os campos de revisão são **internos**. Só o `LegalCatalogReader` e o `LegalVersion` os leem, para decidir a ativação (BR1.1). Nenhuma resposta da API (`LegalDtos`) e nenhuma tela os mostra, e o `TermsV3ReacceptanceTest` confere isso. Em `version.properties`, os acentos vão como escape Unicode (`í`), porque o arquivo é lido em ISO-8859-1.

Os textos da v3 dizem o que o código faz (revisão 03): os limites valem antes de cada compra, e as vendas não passam por eles. O bot nunca compra token com dado `unknown`, mas uma posição aberta pode ser vendida. A venda total para com o kill switch, uma divergência ou uma restauração, e só o saque do SOL livre fica garantido. A taxa da rede sai da carteira principal no modo Assinada e da carteira do bot quando ela executa a ordem.

Com a v3 vigente, todo convidado que aceitou só a v1 recebe `403 TERMS_REACCEPTANCE_REQUIRED` com `currentVersion=v3` em todas as rotas fora dos prefixos permitidos. As exceções são a leitura da carteira do bot (`GET /api/v1/real/bot-wallet`) e as rotas de saque. Com `MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE=true` e algum campo de implantação ausente, a v3 é ignorada como não publicável e a v1 continua vigente.

Os testes de integração usam o relógio fixo 2026-10-28. Nesse instante a v3 é futura e a v1 é a vigente do catálogo real, o que mantém esses testes determinísticos. A v3 como vigente é provada com o relógio em 2026-11-20 no `TermsV3ContentTest` e no `TermsV3ReacceptanceTest`.

Os testes, o smoke e o ponta a ponta usam o catálogo de teste `apps/api/src/test/resources/terms-test/reviewed/`, com uma v2 marcada como revisada só para teste. O `infra/docker-compose.test.yml` monta esse catálogo, só para leitura, e ele nunca vai para a imagem.

## Checklist de abertura do beta

Antes de abrir o beta aos convidados:

1. **Texto final:** feito na v3 (2026-11-20). Um texto novo vira uma pasta nova (`terms/v4/`), nunca uma edição da v3.
2. **Revisão registrada:** feita em `terms/v3/version.properties`, com `legalReviewedAt`, `legalReviewedBy` e `effectiveAt`.
3. **Campos de implantação:** definir `MEMETRADE_LEGAL_STORAGE_LOCATION`, `MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER` e `MEMETRADE_LEGAL_DPO_EMAIL` no ambiente (Parameter Store na nuvem) e ligar `MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE=true` em produção.
4. **Conferência:** o componente de saúde `legalDocuments` mostra `UP`, `currentVersion=v3` e `publishable=true`. `/privacidade` não mostra "a definir" nem o aviso de versão não publicada, e o log de subida só traz `WARN Legal version v2 ignored: not legally reviewed` (o rascunho substituído).
5. **Banco novo:** os dados de desenvolvimento e teste são descartados antes da abertura (ambiente novo, FR3.3 do intent).
6. **Rede interna da nuvem:** `MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS` recebe só as sub-redes privadas dos apps `web` e `admin`, nunca a do balanceador.
7. **Retenção e auditoria (U2):** cumprir o checklist de `docs/privacy/u2-retencao-e-auditoria.md`: chave HMAC da auditoria no gerenciador de segredos e as duas consultas de conferência vazias. A tabela de retenção de `/privacidade` vem do catálogo do Privacy.
8. **Novo aceite (U3):** a partir da U3, toda versão nova que passa a ser a vigente efetiva ativa a trava de novo aceite para todos os convidados. Siga "Publicar uma versão nova dos termos" em [`u3-reacceptance.md`](u3-reacceptance.md).

## Páginas dos apps

- `/termos` e `/privacidade` existem nos dois apps, sem sessão e fora da entrada do administrador. São montadas no servidor do app, que lê a API pela origem interna `API_INTERNAL_ORIGIN` (no Compose, `http://api:8080`). Essa origem nunca vai para o navegador nem para uma variável `NEXT_PUBLIC_*`.
- O servidor de cada app guarda uma resposta de sucesso por 300 s por URL. Erros e `404` nunca são guardados, e a página mostra "Tentar de novo".
- `?versao=vN` mostra uma versão do histórico com a faixa "Esta não é a versão vigente". Uma versão inexistente mostra "Versão não encontrada".
- O rodapé (`SiteFooter`) aparece em todas as telas dos dois apps e nas páginas legais. No app do convidado, com sessão, ele mostra também "Versão aceita: vX", lida da Conta uma vez por sessão.
- "Fazer um pedido" leva à Conta com sessão e à entrada sem sessão. No app do administrador, leva à entrada do app do convidado.

## Carga

`LOAD_SCENARIO=legal scripts/load-test.sh` (perfis `test,loadtest`) roda 20 usuários por 5 minutos: metade nas páginas e metade na API. O cenário reprova com p95 de página acima de 2 s, p95 da API acima de 300 ms ou qualquer 5xx. O relatório fica em `docs/perf/u1-legal-documents-<data>.md`. A opção `legal` do workflow `load-test.yml` é acrescentada pelo responsável do projeto.
