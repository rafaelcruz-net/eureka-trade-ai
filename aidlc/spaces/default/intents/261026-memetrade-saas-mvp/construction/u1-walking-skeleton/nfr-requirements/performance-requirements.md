# Requisitos de desempenho — U1 Esqueleto de ponta a ponta

Metas mensuráveis da Unit `u1-walking-skeleton` para os fluxos de [functional-spec.md](../functional-design/functional-spec.md) (F1 login, F4 lista, F5 coleta) e as regras de [rules.md](../functional-design/rules.md). Derivam do NFR1, NFR3, NFR11 e NFR14 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e das regras comuns de [contract-summary.md](../../../inception/contract-design/contract-summary.md) (tempo limite do cliente de 10 s). Decisões em [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q1).

Ambiente de referência: o ambiente local no Windows com Docker Compose (API, app do convidado e PostgreSQL), com o banco já com as migrações aplicadas e a fonte simulada coletando.

## Requisitos

| ID | Requisito | Métrica e meta | Condição de carga | Como medir | Origem |
|---|---|---|---|---|---|
| NFR3.1 | Tempo de resposta da API nos fluxos da U1 | p95 ≤ 300 ms para desafio, verificação e primeira página da lista | 20 usuários simultâneos, cada um com uma ação a cada 2 s, por 5 minutos | Teste de carga leve contra o ambiente local, medindo o tempo do servidor (sem rede do navegador) | NFR3, Q1 |
| NFR3.2 | Tempo de tela da lista do scanner | p95 ≤ 2 s do pedido da página até a lista visível com valores | 20 usuários simultâneos | Teste de ponta a ponta com marcação de tempo no navegador | NFR3 |
| NFR1.2 | Intervalo de coleta da fonte simulada | Coleta a cada 30 s ou menos, para que a idade exibida fique ≤ 60 s com a fonte operante | Sempre | Teste com relógio injetável conferindo a idade calculada | NFR1 |
| NFR11.1 | Acessibilidade básica nas telas S1 e S3 | 100% das ações do fluxo operáveis por teclado, foco visível, rótulos visíveis, contraste ≥ 4,5:1 no tema escuro, cor nunca como único indicador | Não se aplica | Teste de ponta a ponta só com teclado e verificação de contraste dos tokens de cor | NFR11 |
| NFR14.1 | Interface em português do Brasil, só para computador | 100% dos textos de interface em pt-BR; aviso abaixo de 1280 px de largura | Não se aplica | Verificação do catálogo de textos e teste de largura | NFR14 |

## Orçamento de tempo de uma requisição da lista

| Etapa | Orçamento (p95) |
|---|---|
| Autenticação e conferência da sessão (BR1.14) | 20 ms |
| Consulta da página ordenada com cursor (BR3.6) | 150 ms |
| Montagem dos valores KNOWN/UNKNOWN e da cobertura (BR3.1, BR3.5) | 50 ms |
| Serialização da resposta | 30 ms |
| Folga | 50 ms |
| **Total** | **300 ms** |

## Fora desta Unit

- Desempenho do ciclo dos bots e capacidade de 60 bots ativos (NFR2): U5.
- Atualização ao vivo do painel em até 60 s (FR12.2): U5 e U9.
- Metas de tempo com fontes reais e hospedagem: revistas quando houver hospedagem (NFR3 é provisório).
