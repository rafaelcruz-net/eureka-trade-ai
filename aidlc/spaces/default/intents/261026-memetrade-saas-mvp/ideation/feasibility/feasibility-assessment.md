# Avaliação de viabilidade — MemeTrade Analytics

**Data da avaliação:** 26/10/2026  
**Base:** PRD MemeTrade Analytics v1.3, respostas confirmadas na captura de intenção e pesquisa de mercado, respostas desta etapa e fontes oficiais consultadas nesta data.

## Síntese executiva

**Viabilidade condicional para discovery e beta PAPER; não há evidência suficiente para autorizar uma oferta SaaS aberta ou trading REAL automatizado.** A primeira etapa pode avançar como trabalho de validação, desde que trate integração de dados, direitos de uso, privacidade e enquadramento jurídico como questões abertas. O PRD mantém corretamente o modo REAL atrás de gates de segurança, auditoria, revogação e revisão jurídica.

O projeto é greenfield. O PRD prescreve uma stack candidata (Next.js/TypeScript, Java 21/Spring Boot e Docker Compose em Windows), mas a experiência disponível nessa stack ainda será mapeada na Sprint 0. Não há conta AWS, orçamento aprovado, fornecedor contratado, região de dados definida nem data externa. Portanto, não é possível sustentar estimativas confiáveis de custo, capacidade ou prazo antes da Sprint 0.

## Avaliação por dimensão

| Dimensão | Avaliação | Evidência e condição |
|---|---|---|
| Produto e escopo do beta | Viável para validação com convidados | Público inicial no Brasil; descoberta, avaliação, PAPER e P&L na primeira disponibilização. As metas de uso e a dor concreta do scanner ainda dependem de discovery com usuários. [Intent Statement](../intent-capture/intent-statement.md) |
| Dados e integrações Solana | Incerta, avaliável por prova limitada | O PRD lista várias fontes de descoberta, APIs de dados, cotações e RPC/WebSocket. Cobertura, frescor, divergência, quotas e permissões de exibição/redistribuição não foram comprovados. A etapa decide comparar duas fontes candidatas sem escolher ou contratar fornecedor. [Market Research](../market-research/competitive-analysis.md) · [Build vs. Buy](../market-research/build-vs-buy.md) |
| PAPER e P&L | Viável com validação de método | O PRD descreve simulação, ledger separado e reconciliação. A validade dependerá de hipóteses explícitas para spread, slippage, taxas, fills e cotações, além de cenários determinísticos; não há benchmark de execução disponível ainda. |
| Trading REAL e delegação | Bloqueado para ativação | Não foi demonstrado mecanismo de delegação Solana com limites agregados, vencimento, revogação e compatibilidade com os swaps pretendidos. O PRD condiciona a ativação a testes de revogação, revisão independente, auditoria e avaliação jurídica. Nenhuma movimentação de fundos ou implantação on-chain está autorizada por esta análise. |
| Privacidade e dados | Em aberto, com caminho de avaliação | O produto prevê endereços públicos de carteira, contas/tenants e eventos de auditoria. A residência dos dados e os prazos de retenção não estão definidos. A LGPD determina o término do tratamento quando a finalidade for alcançada ou os dados deixarem de ser necessários, com conservação apenas nas hipóteses legais; transferência internacional exige hipótese legal e mecanismo válido conforme a regulamentação da ANPD. [LGPD, arts. 15–16](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) · [Regulamento de Transferência Internacional da ANPD](https://www.gov.br/anpd/pt-br/acesso-a-informacao/institucional/atos-normativos/regulamentacoes_anpd/resolucao-cd-anpd-no-19-de-23-de-agosto-de-2024) |
| Enquadramento regulatório | Precisa de parecer jurídico antes da oferta ou execução REAL | A CVM informa que sua competência alcança criptoativos que sejam valores mobiliários e que determinados serviços automatizados de recomendação/consultoria podem estar sujeitos às regras de atividade regulada. O Banco Central publicou regras para prestadores de serviços de ativos virtuais, incluindo a Resolução BCB 520, em vigor desde 02/02/2026. A aplicação ao produto depende das atividades efetivamente prestadas, dos ativos e do fluxo operacional; esta avaliação não faz essa classificação. [CVM: criptoativos](https://www.gov.br/cvm/pt-br/acesso-a-informacao-cvm/perguntas-frequentes-da-cvm/criptoativos-quando-se-aplicam-as) · [CVM: robôs de investimentos](https://www.gov.br/investidor/pt-br/investir/como-investir/profissionais-do-mercado/robos-de-investimentos) · [BCB: novas regras para ativos virtuais](https://www.bcb.gov.br/detalhenoticia/20918/nota?s=08) · [Resolução BCB 520](https://www.bcb.gov.br/estabilidadefinanceira/exibenormativo?numero=520&tipo=resolu%C3%A7%C3%A3o+bcb) |
| Capacidade e plataforma | Estimativa pendente | O workspace não possui stack detectada nem conta AWS. O PRD prescreve tecnologias candidatas; disponibilidade de conhecimento, ambientes e custos deve ser levantada na Sprint 0 antes de detalhar esforço ou implantação. |
| Prazo e custo | Não estimáveis nesta etapa | Não existe data externa nem teto de orçamento aprovado. Não há autorização para contratação. Revisar estimativas após Sprint 0, como definido pelo responsável do projeto. |

## Condições para avançar

1. **Discovery/G0:** validar duas fontes candidatas somente para leitura, comparando campos, cobertura, frescor observado, divergência, quotas, custo projetado e termos para exibição/redistribuição.
2. **Privacidade:** inventariar dados pessoais e de carteira, finalidades, agentes de tratamento, retenção, direitos dos titulares, fornecedores e eventuais transferências internacionais; definir residência com base nessa análise e revisão jurídica.
3. **Regulatório:** obter análise de assessoria jurídica brasileira sobre a atividade concreta — descoberta, sinais, recomendações, execução por conta do usuário, serviços de ativos virtuais, prevenção à lavagem de dinheiro, tributação e oferta a terceiros — antes de disponibilização SaaS e obrigatoriamente antes de qualquer função REAL.
4. **Capacidade e custo:** mapear experiência na stack prescrita, necessidade de apoio especializado, disponibilidade humana, opções de ambiente e estimativas; não assumir que AWS já está provisionada.
5. **PAPER:** manter fundos, ordens, ledger e P&L de PAPER separados de REAL, e documentar as hipóteses do simulador antes de interpretar resultados como desempenho.
6. **REAL:** manter desabilitado até evidência de delegação limitada e revogável, validação independente, testes de segurança/reconciliação, responsáveis nomeados e aprovação explícita posterior do responsável do projeto.

## Riscos de inviabilidade ou atraso

- Termos de um provedor podem impedir exibição comercial ou redistribuição de dados, tornando necessário trocar a fonte ou construir uma alternativa.
- O serviço pode entrar em categorias reguladas conforme a função efetivamente prestada. A falta de assessoria jurídica e de orçamento impede resolver isso nesta etapa.
- Sem método validado de PAPER, P&L e score podem parecer precisos sem representar fills ou risco observáveis.
- A ausência de conta AWS, limite de gasto e responsáveis especializados impede fechar custo e cronograma.
- Delegação autônoma que respeite limites globais e revogação pode ser incompatível com parte dos swaps/DEXs pretendidos; falha nesse ponto posterga ou elimina a função REAL, sem impedir o beta PAPER.

## Fontes e limites

As informações de mercado sobre concorrentes e APIs estão registradas em [Análise competitiva](../market-research/competitive-analysis.md), [Tendências de mercado](../market-research/market-trends.md) e [Construir, comprar ou integrar](../market-research/build-vs-buy.md). A pesquisa pública comprova que determinadas capacidades são anunciadas/documentadas; não comprova qualidade, desempenho, autorização comercial para redistribuição, preferência dos convidados ou retorno financeiro.

As fontes legais acima foram consultadas em 26/10/2026. São referências para identificar perguntas e controles a revisar, não parecer jurídico nem conclusão de que o MemeTrade está sujeito a uma categoria regulatória específica.
