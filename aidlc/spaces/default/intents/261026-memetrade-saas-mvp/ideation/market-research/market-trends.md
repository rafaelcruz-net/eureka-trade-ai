# Tendências e dependências de mercado — MemeTrade Analytics

## Síntese
O mercado observado oferece ferramentas que cobrem partes sobrepostas de descoberta, análise de tokens e carteiras, e execução na Solana. Isso é evidência de oferta de produtos, não de adoção ou preferência entre os convidados do Brasil. As tendências abaixo orientam investigação; não predizem retorno nem segurança. [Q1] [Q2] [Fontes: Axiom](https://trade-on-axiom.com/product) · [DEX Screener](https://docs.dexscreener.com/api/reference)

## Sinais observados
| Sinal | Evidência pública | Consequência para discovery | Fonte |
|---|---|---|---|
| Descoberta por novas pools/tokens e filtros de mercado está disponível por interfaces e APIs | DEX Screener documenta busca e campos como volume, liquidez, mudança de preço e criação de pool. Axiom descreve feeds de descoberta e novas migrações. | Cobertura, atualização e tratamento de campos ausentes podem ser critérios comparáveis; medir com as tarefas reais dos convidados. | [DEX Screener](https://docs.dexscreener.com/api/reference); [Axiom](https://trade-on-axiom.com/product) |
| Dados e análise de wallets são vendidos como capacidades de plataforma/API | Birdeye anuncia analytics de token, liquidez, P&L de wallet e APIs/WebSockets. | Avaliar se comprar esses dados reduz esforço e qual camada ainda precisa ser construída para normalização, confiança e experiência do produto. | [Birdeye Data](https://birdeye.so/data-api) |
| Agregadores de swap separam cotação/roteamento de uma transação pronta para assinatura e envio | Jupiter Swap API oferece /order e /execute para transação assinada/landing gerenciado e /build para instruções customizadas. | A cotação isolada pode apoiar avaliação e PAPER; uma integração de execução real é uma decisão separada, condicionada aos gates do PRD. | [Jupiter Developers](https://developers.jup.ag/docs/swap) |
| APIs publicam quotas e condições de uso que variam por produto/plano | DEX Screener documenta limites por endpoint e termos com restrições de finalidade/redistribuição. Birdeye publica planos com limites de requisição e unidades de consumo. | Uma prova técnica deve medir chamadas necessárias, cobertura e custo, além de validar contratualmente a redistribuição no SaaS. | [DEX Screener API](https://docs.dexscreener.com/api/reference); [DEX Screener Terms](https://docs.dexscreener.com/api/api-terms-and-conditions); [Birdeye Pricing](https://docs.birdeye.so/docs/pricing) |
| A oferta é dinâmica e novas ferramentas surgem em listas da categoria | Diretórios de mercado listam muitos terminais e scanners; a lista é atualizada por terceiros e pode ficar desatualizada. | Revalidar nomes, URLs e recursos antes de decisão de compra; manter arquitetura por adapters onde a evidência justificar. | [Diretório de scanners](https://dexcharts.com/categories/trenches-scanners); [panorama de terminais](https://jumpbit.io/en/resources/best/trading-terminals) |

## Dependências e riscos a monitorar
- **Qualidade e frescor dos dados:** timestamp, origem, cobertura por venue, divergência entre providers e tratamento de campos ausentes. A documentação mostra campos nullable em alguns dados; não interpretar ausência como zero ou segurança. [Q3] [Fontes: DEX Screener](https://docs.dexscreener.com/api/reference)
- **Termos comerciais e técnicos:** limite por minuto/segundo, chaves de produção, preço por volume, licença para exibir ou redistribuir dados e possibilidade de mudanças/encerramento. Termos precisam de revisão pelo responsável adequado antes de uso comercial. [Q7] [Q8] [Fontes: DEX Screener](https://docs.dexscreener.com/api/api-terms-and-conditions) · [Birdeye](https://docs.birdeye.so/docs/pricing)
- **Execução e autorização:** a API de swap constrói cotações ou transações; isso não demonstra delegação autônoma com teto global, expiração e revogação. A integração REAL segue bloqueada até validação técnica, auditoria e avaliação jurídica confirmadas na iniciativa. [Q5] [Fontes: Jupiter](https://developers.jup.ag/docs/swap)
- **Risco de mercado:** memecoins têm liquidez e preços variáveis; métricas de mercado não asseguram legitimidade nem evitam perdas. Esta pesquisa não valida modelo de risco nem promete detecção de rug pulls. [Q1] [Q4]
- **Regulação e oferta no Brasil:** o PRD exige avaliação profissional de questões aplicáveis, LGPD, tributação e oferta de serviços. Esta etapa não emite parecer nem conclui o enquadramento. A pessoa responsável e o orçamento jurídico ainda estão pendentes. [Q6] [Q8]

## Hipóteses para validar
| Hipótese | Como verificar | Estado |
|---|---|---|
| Traders convidados valorizam cobertura e atualização como primeiro critério | Entrevistas e tarefas comparáveis; confrontar origem/frescor com a percepção do usuário | Prioridade indicada, evidência de usuário pendente [Q3] [Q10] |
| Integração e transparência seriam diferenciais | Testar conceito contra ferramentas reais e identificar se resolve uma dor confirmada | Hipótese, sem prova competitiva [Q1] [Q4] |
| Dados contratados podem acelerar a primeira entrega | PoC limitada com dois fornecedores candidatos, medindo campos, latência observada, custo e licença | Sem fornecedor escolhido [Q7] [Q8] |
| Simulação PAPER e P&L confiável aumentam valor do beta | Avaliar com convidados após apresentar um fluxo de demonstração | Incluídos na primeira disponibilização; benefício a validar [Q5] |

## Assumptions & Open Questions
Não há pesquisa primária com usuários, cotação comercial, avaliação jurídica, benchmark de latência ou confirmação de licença para redistribuição. Esses itens permanecem pendentes; fontes e condições devem ser revalidadas antes de contratar ou integrar. [Q1] [Q5] [Q6] [Q7] [Q8]
