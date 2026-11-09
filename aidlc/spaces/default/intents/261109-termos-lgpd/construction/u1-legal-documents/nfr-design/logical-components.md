# Componentes lógicos — U1 `u1-legal-documents`

Conversa: português (Brasil). Liga os padrões de `performance-design.md`, `security-design.md`, `reliability-design.md`, `scalability-design.md` e `observability-design.md` aos componentes.

## Inventário

| Componente | Processo | Responsabilidade | Domínio de falha | Raio de impacto |
|---|---|---|---|---|
| Catálogo de documentos (`identity`) | API | ler, validar, ativar e montar as versões na subida | subida da API | sem versão ativável a API não sobe (falha rápida, intencional) |
| Porta da tabela de retenção (J5, `identity`) | API | fonte da tabela; implementação padrão pela versão | subida da API | tabela vazia só em legacy |
| Rotas de documentos (`identity` web) | API | servir as respostas prontas com cache e `ETag` | requisição | uma rota |
| `LegalDocumentsRateLimiter` | API | limite por IP com isenção da rede interna | memória da instância | só as rotas de documentos |
| Indicador `legalDocuments` | API | estado publicável | saúde | não afeta a prontidão |
| Páginas `/termos` e `/privacidade` | web e admin | montar no servidor, cache de dados de 5 min | requisição de página | só a página |
| `SiteFooter` | web e admin | links e versão aceita | renderização | nenhum |
| Cenário `legal` | script de carga | medir p95 | fora da produção | nenhum |

## Recursos compartilhados

- `ClientIpResolver` e `SecurityHeadersFilter`, existentes na API.
- O controle de acesso (`middleware`) e a política de conteúdo dos apps.

## Isolamento

A U1 não toca banco de dados nem outro módulo em tempo de requisição. Uma falha nela não se propaga para as demais rotas.
