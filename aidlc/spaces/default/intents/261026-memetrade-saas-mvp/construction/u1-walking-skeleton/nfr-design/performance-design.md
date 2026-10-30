# Desenho de desempenho — U1 Esqueleto de ponta a ponta

Como a Unit `u1-walking-skeleton` cumpre as metas de [performance-requirements.md](../nfr-requirements/performance-requirements.md) (NFR3.1, NFR3.2, NFR1.2, NFR11.1, NFR14.1) para os fluxos F1, F4 e F5 de [functional-spec.md](../functional-design/functional-spec.md), com a stack de [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md). Decisões em [nfr-design-questions.md](nfr-design-questions.md) (Q2, Q4, Q5).

## Decisões

| Decisão | Escolha | Alternativa rejeitada | Por quê | Requisito |
|---|---|---|---|---|
| Acesso à lista | Consulta direta ao PostgreSQL com índice pela ordem da lista e paginação por cursor; sem cache na aplicação (Q5) | Cache em memória da primeira página por 5 s | Com até 500 tokens a consulta indexada cabe no orçamento de 150 ms; um cache adicionaria um segundo frescor a explicar na tela e um caminho para mostrar dado mais velho que a idade exibida | NFR3.1, NFR3.4 |
| Pool de conexões | Pool padrão do Spring (HikariCP) com 10 conexões, tempo de espera por conexão de 2 s | Pool maior | 20 usuários com uma ação a cada 2 s geram cerca de 10 pedidos por segundo; com 150 ms por consulta, 10 conexões dão folga de mais de 5 vezes | NFR3.1, NFR3.3 |
| Intervalo de coleta | A cada 15 s (Q4) | 30 s | Uma coleta pode falhar e a idade exibida continua abaixo de 60 s | NFR1.2 |
| Teste de carga | Perfil de teste de carga que eleva o limite de tentativas; demais configurações iguais ao perfil padrão (Q2, ver [security-design.md](security-design.md)) | Login espaçado; IPs de loopback distintos | Mede desafio, verificação e lista sob a mesma carga, sem desligar o limite no perfil normal | NFR3.1, NFR6.5 |
| Medição | Histograma de latência por rota no servidor (ver [observability-design.md](observability-design.md)) e marcação de tempo no navegador no Playwright | Medir só no cliente | O NFR3.1 mede o tempo do servidor; o NFR3.2 mede a tela | NFR3.1, NFR3.2 |

## Consulta da lista

- Índice composto que segue a ordem padrão da lista mais o identificador do token, para o cursor ser estável (BR3.6).
- O cursor carrega a última chave de ordenação e o instante de corte da primeira página; a consulta usa `WHERE (chave) > (cursor)` com `LIMIT`, nunca `OFFSET`.
- As observações vigentes de cada token são lidas com a página em uma única consulta (junção ou segunda consulta por lista de IDs), nunca uma consulta por token.
- `total` é calculado na primeira página dentro do mesmo instante de corte; com até 500 tokens a contagem é barata (C2).

Esboço do formato da consulta (ilustrativo):

```sql
SELECT t.id, t.symbol, o.field, o.status, o.value, o.observed_at
FROM token t JOIN market_observation o ON o.token_id = t.id
WHERE (t.sort_key, t.id) > (:lastSortKey, :lastId)
  AND t.first_seen_at <= :cutoff
ORDER BY t.sort_key, t.id
LIMIT :limit;
```

## Orçamento de tempo da lista (p95)

| Etapa | Orçamento | Como o desenho cumpre |
|---|---|---|
| Autenticação e conferência da sessão | 20 ms | Uma leitura por chave primária da sessão com usuário e tenant (ver [security-design.md](security-design.md)) |
| Consulta da página | 150 ms | Índice composto e cursor |
| Montagem de KNOWN/UNKNOWN e cobertura | 50 ms | Em memória sobre no máximo 100 tokens da página |
| Serialização | 30 ms | JSON padrão do Spring, decimais como texto |
| Folga | 50 ms | — |

## Tela do scanner

- A página S3 busca a primeira página da lista assim que a sessão é renovada (F2) e mostra "Carregando" até a resposta; o tempo de tela do NFR3.2 vai do pedido da página até a lista visível com valores.
- Os textos de interface ficam num catálogo pt-BR único (NFR14.1); abaixo de 1280 px aparece o aviso de tela só para computador.
- Acessibilidade (NFR11.1): componentes com foco visível e rótulos visíveis, tokens de cor do tema escuro com contraste mínimo de 4,5:1 conferido por teste, e estados (`unknown`, desatualizado, erro) sempre com texto além da cor. O teste de ponta a ponta percorre S1 e S3 só com teclado.

## Fora desta Unit

- Ciclo dos bots e 60 bots ativos (NFR2): U5.
- Canal ao vivo do painel: U5 e U9.
