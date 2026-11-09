# Perguntas de NFR — U1 `u1-legal-documents`

Conversa: português (Brasil). Base:
- `construction/u1-legal-documents/functional-design/` (BR1.1 a BR1.16);
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR9);
- `inception/contract-design/contract-summary.md` (L1);
- a base de conhecimento do código (limite de tentativas, logs, saúde, cenários de carga existentes).

Já definidos: p95 das páginas até 2 s com 20 acessos (NFR3); acessibilidade básica e AA nas páginas legais (NFR4); texto nunca como HTML.

## Q1. Como guardar em cache os documentos públicos?

A) Os documentos só mudam com uma implantação nova (são lidos na subida). Por isso:
- a API responde com `Cache-Control: public, max-age=300` e uma `ETag` que é a própria versão;
- o app gera as páginas no servidor e as reaproveita por até 5 minutos.

Meta: p95 da rota de documentos até 300 ms no servidor (recomendado)

B) Sem cache
X) Other (please specify)

[Answer]: A

## Q2. Como proteger as rotas públicas contra abuso?

A) Aplicar o limite de tentativas que já existe para rotas sem sessão: 60 requisições por minuto por IP nas rotas de documentos, com `429 RATE_LIMITED` e `Retry-After`. Só leitura, CORS só para as duas origens dos apps, sem credenciais (recomendado)
B) Sem limite (é conteúdo público)
X) Other (please specify)

[Answer]: A

## Q3. Como medir o p95?

A) Um cenário novo `legal` no script de carga existente: 20 acessos simultâneos por 5 minutos alternando `/termos` e `/privacidade` (app) e a rota de documentos (API). Aprova com p95 da página até 2 s e da API até 300 ms, sem erro 5xx. Ele roda junto dos outros cenários no Build and Test (recomendado)
B) Medir só no spec de ponta a ponta
X) Other (please specify)

[Answer]: A

## Q4. Que sinais de operação?

A) Três sinais:
- um aviso no log na subida para cada versão ignorada, com a versão e o motivo e sem texto do documento;
- um indicador de saúde `legalDocuments` com a versão vigente e se ela é publicável. Fica `UP` quando publicável e `DEGRADED` quando não é, sem derrubar a saúde geral;
- uma métrica de requisições por rota de documentos.

(recomendado)

B) Só o aviso no log
X) Other (please specify)

[Answer]: A

## Q5. Que cuidados de segurança nas páginas?

A) As páginas seguem os cabeçalhos de segurança que os apps já usam: política de conteúdo sem scripts de terceiros, `X-Content-Type-Options` e proteção contra enquadramento. O texto é sempre escapado. Nada da página guarda dado no navegador. O e-mail do encarregado aparece como texto e link `mailto:`, sem formulário (recomendado)
B) Outra regra (diga qual)
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Cache: `Cache-Control: public, max-age=300` e `ETag` = versão; páginas geradas no servidor reaproveitadas por até 5 min; p95 da rota de documentos até 300 ms.
- Abuso: limite existente de 60 requisições por minuto por IP nas rotas de documentos, `429 RATE_LIMITED` com `Retry-After`; só leitura; CORS só para os dois apps, sem credenciais.
- Medição: cenário de carga `legal` com 20 acessos por 5 min; página p95 até 2 s, API p95 até 300 ms, sem 5xx; roda no Build and Test.
- Operação: aviso no log por versão ignorada (sem texto); saúde `legalDocuments` (`UP` publicável, `DEGRADED` não publicável, sem derrubar a saúde geral); métrica de requisições por rota.
- Segurança: cabeçalhos dos apps, texto escapado, nada no navegador, e-mail do encarregado como `mailto:`.

- Looks correct
- Request changes

[Answer]: Looks correct
