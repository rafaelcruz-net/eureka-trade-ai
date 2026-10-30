# Resumo da iniciativa — MemeTrade Analytics

**Data:** 26/10/2026  
**Decisão recomendada:** **Seguir, com condições.** Avançar para a fase de elaboração (requisitos, histórias e design) com a Sprint 0 de discovery em paralelo. Trading REAL, cobrança pública e contratações continuam bloqueados.

## Intenção e problema

Validar primeiro a descoberta e a avaliação de tokens de Solana para traders convidados no Brasil, já familiarizados com Solana e carteiras digitais. A primeira disponibilização reúne descoberta, avaliação, bots PAPER e P&L reconciliável. A automação com fundos reais é uma evolução posterior, sujeita a gates próprios. O critério inicial de sucesso é integridade: dados com origem e atualização visíveis, operações sem duplicidade, respeito aos limites e P&L reconciliável. Metas de uso serão definidas depois do discovery. Fonte: [Intent Statement](../intent-capture/intent-statement.md).

Você decide prioridades e aprova entregas, desenvolvendo com apoio de IA. Os convidados contribuem com feedback. Responsáveis por operações, segurança, jurídico e auditoria externa ainda serão definidos. Fonte: [Stakeholder Map](../intent-capture/stakeholder-map.md).

## Validação de mercado

A categoria de scanners, terminais e APIs para Solana é ativa (DEX Screener, Birdeye, Axiom, Photon, GMGN, BullX). A pesquisa pública confirma capacidades anunciadas, mas não qualidade, preferência dos convidados nem resultado financeiro. A combinação de dados transparentes, avaliação explicável, PAPER e P&L reconciliável é uma **hipótese de posicionamento**, não uma vantagem demonstrada, porque outras ferramentas já reúnem descoberta, análise e execução. Tamanho de mercado e preço não foram estimados por falta de base. Fonte: [Competitive Analysis](../market-research/competitive-analysis.md).

## Viabilidade e riscos

| Tema | Situação | Condição para avançar |
|---|---|---|
| Beta PAPER e discovery | Viável com condições | Comparar duas fontes candidatas no G0; documentar hipóteses da simulação |
| Dados e fornecedores | Incerto | Cobertura, frescor, quotas, custo e licença de redistribuição a validar (C-06) |
| Regulação | Parecer necessário antes de SaaS e REAL | Assessoria jurídica brasileira ainda sem responsável nem orçamento (C-09) |
| Privacidade | Em aberto | Inventário de dados, retenção mínima e residência a decidir (C-07, C-08) |
| Trading REAL | Bloqueado | Delegação limitada e revogável, auditoria, testes e aprovação explícita posterior |
| Capacidade, custo e prazo | Não estimáveis ainda | Mapear na Sprint 0; sem data externa e sem orçamento aprovado (C-11 a C-13) |

Riscos de maior peso no RAID log: enquadramento regulatório (R-01), licença dos fornecedores (R-02), delegação para REAL (R-03), fidelidade do PAPER (R-04), cobertura e frescor dos dados (R-05), isolamento entre contas (R-06), falta de orçamento e responsáveis (R-07) e adoção (R-08). **Decisão:** aceitos com as mitigações registradas, com revisão ao fim da Sprint 0. Fontes: [Feasibility Assessment](../feasibility/feasibility-assessment.md), [Constraint Register](../feasibility/constraint-register.md).

## Limite do escopo

- **Must (beta por convite):** acesso por convite e carteira associada sem expor chave privada; scanner e avaliação com fonte, frescor e cobertura, `unknown` em vez de zero e NO_TRADE quando faltarem dados; um fluxo de bot PAPER com limites e pausa; P&L e ledger PAPER reconciliáveis; isolamento, permissões e auditoria como base; validação do G0.
- **Should:** filtros salvos, watchlist, alertas, métricas avançadas, administração ampla e planos pagos.
- **Fora do primeiro beta:** trading REAL, cobrança pública, SaaS aberto, alavancagem, derivativos, outras blockchains, copy trading, custódia, saques administrados e manipulação de mercado.
- Proto-Units: PB-00 Discovery e G0, PB-01 Acesso seguro, PB-02 Scanner e avaliação, PB-03 Bot PAPER, PB-04 Ledger e P&L (Must); PB-05 a PB-07 (Should).

Fontes: [Scope Document](../scope-definition/scope-document.md), [Intent Backlog](../scope-definition/intent-backlog.md).

## Visão do conceito

Sete telas de baixa fidelidade para computador, em tema escuro e em português: convite e entrada, painel inicial (primeira tela após o login), scanner em tabela com painel lateral, assistente do bot PAPER em 4 etapas, acompanhamento do bot, P&L e ledger PAPER, e conta. O modo PAPER tem rótulo persistente e nenhuma tela oferece REAL. Cinco pontos abertos foram aceitos e seguem para requisitos e refinamento: canal de feedback, lista de bots, edição de limites, rótulo do indicador de risco e critério de contraste. Fonte: [Wireframes](../rough-mockups/wireframes.md).

## Plano de equipe e recursos

- Projeto solo: você conduz o desenvolvimento com apoio de IA. A formação de equipe não se aplicou, então não há avaliação de equipe (team assessment).
- Sem gastos na próxima fase. Estimativas e pedido de orçamento serão apresentados após a Sprint 0.
- Papéis humanos de operações, segurança, jurídico e auditoria externa precisam ser nomeados antes do beta e antes de qualquer REAL (C-14).

## Recomendação de continuidade

**Seguir, com condições:**

1. Iniciar a fase de elaboração (descoberta de práticas, requisitos, histórias, mockups refinados e design) usando este escopo.
2. Executar a Sprint 0 em paralelo: entrevistas com convidados, comparação de duas fontes no G0, inventário de dados e retenção, mapeamento de capacidade e estimativas.
3. Revisar riscos, estimativas e orçamento ao fim da Sprint 0, antes de compromissos financeiros.
4. Manter bloqueados REAL, cobrança pública, contratações e implantação on-chain até as aprovações específicas.

**Critérios de parada:** se o G0 não encontrar fonte com cobertura e licença adequadas, se o parecer jurídico indicar impedimento para o beta, ou se as entrevistas não confirmarem a dificuldade de descoberta e avaliação, a iniciativa volta a você para nova decisão.
