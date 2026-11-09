# Requisitos de desempenho — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes:
- `construction/u1-legal-documents/functional-design/` (BR1.13);
- `inception/requirements-analysis/requirements.md` (NFR3);
- `inception/contract-design/contract-summary.md` (L1);
- `nfr-requirements-questions.md` (Q1, Q3).

## Metas

| ID | Requisito | Meta | Condição | Como se mede |
|---|---|---|---|---|
| NFR3.1 | Página `/termos` ou `/privacidade` pronta | p95 ≤ 2 s | 20 acessos simultâneos por 5 min, no perfil `loadtest` (limite de documentos elevado, como nos outros cenários) | cenário de carga `legal` (Q3) |
| NFR3.2 | Rota de documentos da API (`/api/v1/legal/current`, `/versions`, `/versions/{v}`) | p95 ≤ 300 ms no servidor | mesma carga | cenário `legal` e métrica `http.server.requests` |
| NFR3.3 | Erros sob carga | 0 respostas 5xx | mesma carga | cenário `legal` |
| NFR3.4 | Cache | As rotas de documentos sobrescrevem o `no-store` geral da API só nas respostas 200 e 304, com `Cache-Control: public, max-age=300` e `ETag` com a versão. Erros e `429` continuam sem cache. No app, o reaproveitamento por 5 minutos vale para o dado buscado na API (cache de dados do servidor), não para a página inteira, que segue dinâmica por causa do nonce da política de conteúdo. `/termos` e `/privacidade` são rotas públicas no controle de acesso dos dois apps | sempre | teste de integração dos cabeçalhos por status; teste do controle de acesso dos apps (Q1) |
| NFR3.5 | Custo da subida | catálogo lido uma vez; nenhuma leitura de arquivo por requisição | sempre | teste de integração |

## Requisitos transversais desta Unit

| ID | Requisito | Verificação |
|---|---|---|
| NFR4.1 | Páginas legais com contraste e estrutura WCAG 2.1 AA; teclado com "Pular para o conteúdo", foco visível, h1 a h3 em ordem, regiões; adaptáveis de 320 px sem rolagem horizontal; tabela de retenção empilhada abaixo de 768 px (BR1.13) | spec de ponta a ponta com navegação por teclado e largura de 320 px; teste de componente |
| NFR9.1 | Volume `Comprehensive`; piso de 80% de linhas no backend e nos apps; testes de ativação (legacy, data futura, publicável, ordem por data) escritos antes do código | relatório de cobertura e CI |

## Orçamento

- Os documentos ficam em memória depois da subida, e a resposta é só serialização.
- A página é montada no servidor, sem chamada extra no navegador.
