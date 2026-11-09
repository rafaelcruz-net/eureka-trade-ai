# Perguntas do NFR Design — U1 `u1-legal-documents`

Conversa: português (Brasil). Base: `construction/u1-legal-documents/nfr-requirements/` (NFR3.x, NFR6.x, NFR7.x, NFR8.x), `functional-design/` e a base de conhecimento do código (`ClientIpResolver`, limitador de consulta de convite, `SecurityHeadersFilter`, indicador de tarefas da auditoria, scripts de carga em `scripts/load/`).

## Q1. Como configurar a isenção da rede interna no limitador?

A) Uma lista de faixas de rede (CIDR) na configuração (`memetrade.legal.rate-limit.internal-networks`), comparada só com o endereço do par direto da conexão, nunca com o IP resolvido nem com `X-Forwarded-For`. A lista não pode incluir balanceadores nem proxies confiáveis.
- Padrão local: a faixa da rede do Compose.
- Produção: vazia até a rede da nuvem ser definida.
- Teste: um `X-Forwarded-For` interno forjado vindo de IP externo recebe `429`.

(recomendado)

B) Isentar por um cabeçalho secreto enviado pelo servidor web
X) Other (please specify)

[Answer]: A

## Q2. Como o app busca os documentos?

A) No servidor, com o cache de dados de 5 minutos. Erro não é guardado em cache; sem nova tentativa automática, a página mostra o erro com "tentar de novo". A origem da API é lida no servidor, como hoje. A `ETag` é aproveitada no cache HTTP do navegador só nas rotas da API acessadas diretamente (recomendado)
B) Buscar no navegador
X) Other (please specify)

[Answer]: A

## Q3. Que limites ter no catálogo?

A) Na subida, cada arquivo de texto pode ter no máximo 200 KB e cada versão, no máximo 100 seções. Uma versão fora disso falha na subida com mensagem clara. A resposta é montada uma vez por versão na subida e reutilizada (recomendado)
B) Sem limites
X) Other (please specify)

[Answer]: A

## Q4. Como fica o cenário de carga `legal`?

A) No mesmo script de carga em Node que já existe:
- 20 usuários virtuais por 5 minutos, metade nas páginas do app e metade na API;
- contra o ambiente com o perfil `loadtest`;
- reprova com p95 de página acima de 2 s, p95 da API acima de 300 ms ou qualquer 5xx;
- escreve o relatório em `docs/perf/`.

(recomendado)

B) Uma ferramenta de carga nova
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Isenção: faixas CIDR em `memetrade.legal.rate-limit.internal-networks`, comparadas só com o par direto (nunca IP resolvido nem `X-Forwarded-For`), sem balanceadores ou proxies; local = rede do Compose; produção vazia até a nuvem; teste com `X-Forwarded-For` forjado recebendo `429`.
- Busca no app: no servidor, cache de dados de 5 min, erro sem cache, sem nova tentativa automática; origem da API lida no servidor.
- Limites do catálogo: 200 KB por arquivo e 100 seções por versão, falha clara na subida; resposta montada uma vez por versão.
- Carga: cenário `legal` no script Node existente, 20 usuários por 5 min, metade páginas e metade API, perfil `loadtest`, reprova com página p95 > 2 s, API p95 > 300 ms ou 5xx; relatório em `docs/perf/`.

- Looks correct
- Request changes

[Answer]: Looks correct
