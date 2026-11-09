# Design de desempenho — U1 `u1-legal-documents`

Conversa: português (Brasil). Atende `construction/u1-legal-documents/nfr-requirements/performance-requirements.md` (NFR3.1 a NFR3.8, NFR4.1). Decisões em `nfr-design-questions.md` (Q2, Q3, Q4).

## Caminho da requisição

1. **Subida:** o catálogo lê as versões uma vez, valida os limites e monta, por versão, a resposta pronta (texto já em seções). Fica tudo em memória (NFR3.5).
2. **API** (`GET /api/v1/legal/current`, `/versions`, `/versions/{v}`): devolve a resposta pronta da versão, mais os campos de implantação e a tabela de retenção da porta J5, montados na subida.
   - Nas respostas 200 e 304, `Cache-Control: public, max-age=300` e `ETag` com o valor `"legal-<versão>"`. Com `If-None-Match` igual, a resposta é 304 sem corpo.
   - Erros e `429` mantêm o `no-store` geral (NFR3.4).
3. **App:** a página busca a API no servidor, com cache de dados de 300 s por URL. Erro não é guardado em cache. A página continua dinâmica por causa do nonce da política de conteúdo (Q2).

## Orçamento de latência (p95)

| Trecho | Orçamento |
|---|---|
| API (serialização da resposta pronta) | ≤ 300 ms sob carga; esperado abaixo de 20 ms |
| Busca do app à API (cache quente) | ~0 ms |
| Renderização da página no servidor | ≤ 1 s |
| Total da página (NFR3.1) | ≤ 2 s |

## Limites (Q3)

Cada arquivo de texto tem até 200 KB e cada versão até 100 seções. Fora disso, a subida falha com mensagem clara. Assim, o tamanho da resposta fica limitado.

## Medição (Q4)

Cenário `legal` no script de carga Node existente (`scripts/load/`):
- 20 usuários virtuais por 5 minutos, metade nas páginas do app e metade na API;
- roda no perfil `loadtest`, com o limite de documentos elevado;
- reprova com p95 de página acima de 2 s, p95 da API acima de 300 ms ou qualquer 5xx;
- relatório em `docs/perf/u1-legal-documents-<data>.md`.
