# Análise competitiva — MemeTrade Analytics

## Contexto e limite das evidências
A iniciativa aprovada prioriza descoberta e avaliação, com bots PAPER e P&L na primeira disponibilização; trading REAL e cobrança ficam condicionados ou adiados. [Fonte upstream: [intent-statement.md](../intent-capture/intent-statement.md)]

O responsável ainda não identificou ferramentas usadas pelos convidados; isso significa que falta pesquisa com os usuários, não que não existam alternativas. A pesquisa pública confirma uma categoria ativa de scanners, terminais e APIs para Solana. As páginas consultadas descrevem capacidades oferecidas pelos próprios produtos; elas não comprovam qualidade comparativa, uso pelo público brasileiro, preferência, desempenho ou resultado financeiro. [Q2] [Q1] [Q9] [Q10]

## Alternativas públicas observadas
| Alternativa | Capacidades publicamente descritas | Relação com a proposta | Limite desta evidência |
|---|---|---|---|
| DEX Screener | Busca de pares e tokens; perfis, preço, volume, liquidez, FDV/market cap e horário de criação do par por API. [Fonte: documentação da API](https://docs.dexscreener.com/api/reference) | Alternativa de descoberta e fonte possível de dados; forte ponto de comparação para cobertura e atualização. [Q10] | Documentação confirma campos e limites de requisição publicados, não cobertura completa das venues do PRD nem autorização para redistribuir o serviço. |
| Birdeye | API de dados com informações de tokens, liquidez, histórico, holders, wallets, P&L e WebSockets em planos publicados. [Produto](https://birdeye.so/data-api) · [preços da API](https://docs.birdeye.so/docs/pricing) | Fonte possível de dados e ferramenta adjacente para analytics e análise de carteiras. | O conteúdo e o custo efetivo dependem do plano e do uso; confirmar endpoints, cobertura, termos e limites em avaliação técnica/comercial. |
| Axiom | Site do produto descreve descoberta de tokens, streams de novas moedas/migrações, monitoramento de carteiras e análises ligadas a execução. [Produto](https://trade-on-axiom.com/product) | Concorrente direto de jornada de descoberta até ação, com monitoramento de atividade de carteiras. | Descrições são do fornecedor; não demonstram desempenho, qualidade dos sinais ou encaixe com a hipótese PAPER/contabilidade auditável. |
| Photon | Terminal de negociação e descoberta, incluindo o feed Memescope, conforme materiais públicos de comparação do setor. [Panorama de terminais](https://jumpbit.io/en/resources/best/trading-terminals) | Alternativa focada em descoberta e negociação rápida de tokens. | Fonte independente de terceiros; validar funcionalidades e disponibilidade diretamente com o produto antes de pontuar. |
| GMGN e BullX | Diretório atual da categoria descreve descoberta, análise de carteiras e execução em múltiplas redes. [Panorama de ferramentas](https://dexcharts.com/categories/trenches-scanners) | Alternativas adjacentes com descoberta e recursos de execução e inteligência. | Diretório de terceiros e mercado sujeito a mudanças rápidas; sem comparação validada de preço, segurança ou qualidade. |
| Jupiter Swap API | Agrega caminhos de swap e retorna cotação/transação para assinatura; separa fluxo de agregação gerenciada e instruções customizadas. [Documentação oficial](https://developers.jup.ag/docs/swap) | Infraestrutura de execução/roteamento, não substitui o scanner nem é evidência de diferenciação de produto. | A documentação distingue a cotação da assinatura e execução; compatibilidade com políticas de delegação do projeto continua por provar. |

## Comparação por critério
As prioridades de comparação fornecidas são: cobertura e frescor dos dados primeiro; facilidade de uso, PAPER e preço depois; risco e explicabilidade em seguida. A ordem é uma hipótese inicial a confirmar com traders convidados, não uma pesquisa representativa. [Q3] [Q9] [Q10]

| Critério | O que a pesquisa pública permite observar | O que falta validar com usuários |
|---|---|---|
| Cobertura e frescor | DEX Screener publica endpoints por token/pool e vários campos de mercado; Birdeye publica dados e WebSockets conforme planos. [Fontes: DEX Screener](https://docs.dexscreener.com/api/reference) · [Birdeye](https://birdeye.so/data-api) | Quais venues e métricas os traders brasileiros realmente usam; atraso observado e dados ausentes. |
| Facilidade de uso | Axiom e Photon apresentam jornadas de descoberta e negociação; a descrição de Axiom inclui telas e sinais integrados. [Fontes: Axiom](https://trade-on-axiom.com/product) · [Photon](https://jumpbit.io/en/resources/best/trading-terminals) | Tarefas que hoje exigem alternar ferramentas e se o fluxo integrado reduz essa fricção. |
| PAPER e P&L | Birdeye expõe análise de wallet/P&L; o PRD propõe simulação PAPER com custos e conciliação. [Fonte: Birdeye](https://birdeye.so/data-api) | Se os terminais usados oferecem um modo simulado comparável e se os convidados valorizam fills explicados e resultados reconciliáveis. |
| Risco e explicabilidade | Produtos e APIs anunciam analytics, dados de carteiras e campos de mercado; nenhum material consultado demonstra que uma pontuação identifica fraude com garantia. [Fontes: Axiom](https://trade-on-axiom.com/product) · [DEX Screener](https://docs.dexscreener.com/api/reference) | Que fatores são úteis, como entendem incerteza e unknown, e que nível de explicação esperam. |
| Preço | A API Birdeye publica vários níveis; condições e limites do DEX Screener variam conforme o serviço contratado. [Fontes: Birdeye](https://docs.birdeye.so/docs/pricing) · [termos DEX Screener](https://docs.dexscreener.com/api/api-terms-and-conditions) | Disposição a pagar do grupo beta; a pesquisa de preço foi adiada para depois do beta PAPER. [Q6] |

## Posicionamento a testar
A proposta inicial combina scanner com origem e frescor visíveis, avaliação explicável, modo PAPER e apuração de P&L. Esta combinação é uma hipótese de posicionamento, não uma vantagem competitiva demonstrada. O relatório público mostra que descoberta, análise de carteiras e execução já aparecem juntas em algumas ferramentas; portanto, apenas reunir essas funções não prova diferenciação. [Q4] [Q10] [Fontes: Axiom](https://trade-on-axiom.com/product) · [panorama de ferramentas](https://dexcharts.com/categories/trenches-scanners)

Uma possível distinção a testar é tornar a cobertura, a atualização e os dados ausentes explícitos e vincular a simulação PAPER aos custos e registros usados no P&L. O resultado da pesquisa com traders deve decidir se essa combinação resolve uma dificuldade real antes de fechar requisitos do scanner. [Q1] [Q3] [Q4] [Q5]

## Lacunas e próximos passos de pesquisa
- Entrevistar os traders convidados sobre sua tarefa recente de descobrir e avaliar tokens; registrar exemplo, ferramenta usada, fricção e resultado esperado. A dificuldade atual permanece hipótese. [Q1] [Q2]
- Repetir a tarefa com alternativas escolhidas pelos próprios participantes; medir cobertura percebida, atualização observada e passos/contextos alternados, sem pressupor que rapidez implica melhor decisão. [Q2] [Q3] [Q9] [Q10]
- Validar diretamente a disponibilidade de recursos, termos de uso, licenças de redistribuição, quotas, preço e suporte dos fornecedores considerados. [Q7] [Q8]
- Não estimar TAM/SAM/SOM nem preço público até existir contagem defensável do segmento e pesquisa de disposição a pagar. [Q5] [Q6]

## Assumptions & Open Questions
Não há evidência primária de entrevistas, métricas de uso ou preferências dos convidados nesta etapa. A dificuldade do usuário, a relevância dos diferenciais e a ordem secundária dos critérios devem ser confirmadas na pesquisa com participantes. [Q1] [Q2] [Q3] [Q4] [Q9]
