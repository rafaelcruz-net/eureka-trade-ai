# Registro de decisões da Ideação — MemeTrade Analytics

Todas as decisões abaixo foram tomadas pelo responsável do projeto em 26/10/2026, nas etapas indicadas. A coluna "Registro" aponta o arquivo de perguntas onde a resposta está gravada.

## Intenção (Intent Capture)

Base: [Intent Statement](../intent-capture/intent-statement.md) e [Stakeholder Map](../intent-capture/stakeholder-map.md).

| ID | Decisão | Registro |
|---|---|---|
| D-01 | Validar primeiro descoberta e avaliação de tokens; automação é objetivo posterior | intent-capture Q1 |
| D-02 | Público do beta: traders convidados no Brasil, familiarizados com Solana e carteiras | intent-capture Q2 |
| D-03 | Sucesso inicial medido por integridade; metas de uso após o discovery | intent-capture Q3 |
| D-04 | Explorar a oportunidade sem data obrigatória; revisar estimativas após a Sprint 0 | intent-capture Q4 |
| D-05 | PAPER entra na primeira disponibilização; REAL e cobrança pública ficam para depois; exclusões do PRD mantidas | intent-capture Q5 |
| D-06 | O responsável decide prioridades e aprova entregas; papéis de operações, segurança, jurídico e auditoria a definir | intent-capture Q6 |
| D-07 | Documentos em português no repositório, resumos no chat, aprovação por etapa; análise antes de código | intent-capture Q7 |
| D-08 | Desenvolvimento pelo responsável com IA; sem contratação nesta etapa | intent-capture Q8 |

## Pesquisa de mercado (Market Research)

Base: [Competitive Analysis](../market-research/competitive-analysis.md).

| ID | Decisão | Registro |
|---|---|---|
| D-09 | Dificuldade atual dos traders tratada como hipótese a validar com convidados | market-research Q1 |
| D-10 | Alternativas usadas pelos convidados ainda não identificadas; levantar na pesquisa | market-research Q2 |
| D-11 | Critérios: cobertura e frescor primeiro; facilidade, PAPER e preço depois; risco e explicabilidade em seguida (ordem secundária é hipótese) | market-research Q3, Q9, Q10 |
| D-12 | Integração e transparência de dados como hipótese de diferenciação, sem afirmar vantagem | market-research Q4 |
| D-13 | Sem estimativa de tamanho de mercado; pesquisa de preço adiada | market-research Q5, Q6 |
| D-14 | Nenhuma fonte escolhida; build, buy ou integrar decidido após validar requisitos, cobertura, custo e licenças | market-research Q7, Q8 |

## Viabilidade (Feasibility)

Base: [Feasibility Assessment](../feasibility/feasibility-assessment.md) e [Constraint Register](../feasibility/constraint-register.md).

| ID | Decisão | Registro |
|---|---|---|
| D-15 | G0 compara duas fontes candidatas antes de selecionar | feasibility Q1 |
| D-16 | Sem conta AWS; avaliar opções no discovery | feasibility Q2 |
| D-17 | Residência de dados ainda indefinida | feasibility Q3 |
| D-18 | Inventariar dados e propor retenção mínima para validação jurídica e de privacidade | feasibility Q4 |
| D-19 | Mapear experiência com a stack na Sprint 0 | feasibility Q5 |
| D-20 | Nenhum bloqueio de calendário conhecido; confirmar prioridades na Sprint 0 | feasibility Q6 |

## Escopo (Scope Definition)

Base: [Scope Document](../scope-definition/scope-document.md) e [Intent Backlog](../scope-definition/intent-backlog.md).

| ID | Decisão | Registro |
|---|---|---|
| D-21 | MVP "Must mais enxuto": scanner e avaliação rastreáveis, um fluxo PAPER, P&L reconciliável e segurança base; filtros salvos, alertas e métricas avançadas adiados | scope-definition Q1 |
| D-22 | Formação de equipe não se aplica (projeto solo) | Etapa pulada com justificativa |

## Mockups (Rough Mockups)

Base: [Wireframes](../rough-mockups/wireframes.md).

| ID | Decisão | Registro |
|---|---|---|
| D-23 | Primeira tela após o login: painel com resumo do bot PAPER e do P&L | rough-mockups Q1 |
| D-24 | Bot PAPER criado por assistente próprio em 4 etapas | rough-mockups Q2 |
| D-25 | Interface em português, com termos de mercado em inglês explicados | rough-mockups Q3 |
| D-26 | Scanner em tabela densa com painel lateral | rough-mockups Q4 |
| D-27 | Apenas computador no beta | rough-mockups Q5 |
| D-28 | Sem guia visual; só tema escuro | rough-mockups Q6 |
| D-29 | Acessibilidade básica no beta; WCAG 2.1 AA antes de abrir ao público | rough-mockups Q7 |
| D-30 | Cinco pontos abertos da revisão dos mockups aceitos na aprovação | Aprovação dos mockups |

## Aprovação e passagem de fase (Approval & Handoff)

| ID | Decisão | Registro |
|---|---|---|
| D-31 | Seguir para a fase de elaboração com a Sprint 0 em paralelo; REAL, cobrança pública e contratações bloqueados | approval-handoff Q1 |
| D-32 | Riscos R-01 a R-08 aceitos com mitigações, revisados ao fim da Sprint 0 | approval-handoff Q2 |
| D-33 | Sem gastos na próxima fase; estimativas e orçamento após a Sprint 0 | approval-handoff Q3 |
| D-34 | Pontos abertos dos mockups levados para requisitos e refinamento | approval-handoff Q4 |

## Decisões adiadas

| Tema | Quando decidir | Referência |
|---|---|---|
| Fornecedores de dados e licença | Após o G0 | C-06 |
| Residência e retenção de dados | Após inventário e revisão jurídica | C-07, C-08 |
| Enquadramento regulatório | Antes de SaaS e antes de REAL | C-09 |
| Conta e região de nuvem | Após a Sprint 0 | C-10 |
| Orçamento e estimativas | Após a Sprint 0 | C-12, C-13 |
| Responsáveis humanos por operações, segurança, jurídico e auditoria | Antes do beta e de REAL | C-14 |
| Metas de uso | Após o discovery | D-03 |
