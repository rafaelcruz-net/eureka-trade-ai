# Registro de decisões da Ideation — Trading REAL

Conversa: português (Brasil). Fontes: `ideation/intent-capture/intent-statement.md`, `ideation/intent-capture/stakeholder-map.md`, `ideation/feasibility/feasibility-assessment.md`, `ideation/feasibility/constraint-register.md`, `ideation/scope-definition/scope-document.md`, `ideation/scope-definition/intent-backlog.md` e os arquivos de perguntas de cada etapa.

## Decisões

| # | Data | Etapa | Decisão | Quem decidiu |
|---|---|---|---|---|
| D-01 | 2026-11-13 | Plano | Plano sob medida sobre `classic`, profundidade Comprehensive, revisões adversariais, sem pesquisa de mercado, formação de equipe, mockups rascunho, descoberta de práticas e otimização pós-lançamento | Fundador |
| D-02 | 2026-11-13 | Intent Capture | Três modos, Assinada como padrão, Automática total como foco; troca a qualquer hora | Fundador |
| D-03 | 2026-11-13 | Intent Capture | Fundador como único revisor; sem G3 nesta fase | Fundador |
| D-04 | 2026-11-13 | Intent Capture | Posição de não custódia, pela responsabilidade compartilhada; parecer recomendado, não obrigatório | Fundador |
| D-05 | 2026-11-13 | Intent Capture | Liberação: devnet, carteira do fundador, até 20 convidados, quarentena de 24 horas | Fundador |
| D-06 | 2026-11-13 | Feasibility | Chave da carteira do bot gerada e mantida só no KMS (AWS KMS, conta nova); produção nunca vê a chave | Fundador |
| D-07 | 2026-11-13 | Feasibility | Sem SDK Solana na produção; a Jupiter monta a transação e a produção só assina | Fundador |
| D-08 | 2026-11-13 | Feasibility | Teto máximo de 10 SOL para o fundador e para cada convidado, configurável no perfil do convidado | Fundador |
| D-09 | 2026-11-13 | Feasibility | Limites: até 10% do teto por ordem, perda diária de 20% com pausa, até 20 ordens por dia por bot | Fundador |
| D-10 | 2026-11-13 | Feasibility | CoinGecko paga entra nesta intent; o REAL não liga com fonte simulada | Fundador |
| D-11 | 2026-11-13 | Feasibility | Testes: devnet e validador local com contas da mainnet clonadas para a Jupiter | Fundador |
| D-12 | 2026-11-13 | Feasibility | Orçamento de até US$ 100 por mês; sem data fixa | Fundador |
| D-13 | 2026-11-13 | Scope | Must: dados reais, carteira, executor, ledger e reconciliação, barreira, modos e termos, Assinada, liberação; Should: saídas automáticas e exportação fiscal | Fundador |
| D-14 | 2026-11-13 | Scope | Tokens só com avaliação de cobertura suficiente, mais lista de bloqueio; tudo em SOL | Fundador |
| D-15 | 2026-11-13 | Scope | Kill switch: bloqueia novas ordens REAL, cancela condicionais das carteiras do bot, saque liberado | Fundador |
| D-16 | 2026-11-13 | Approval | Riscos aceitos, inclusive sem auditoria externa e sem parecer obrigatório | Fundador |
| D-17 | 2026-11-13 | Approval | Seguir para a Inception com as três provas técnicas como condição | Fundador |

## Decisões adiadas

| Decisão | Quando |
|---|---|
| Mudança das regras de `project.md` e `team.md` sobre o REAL | antes da Construction, pelo registro do AI-DLC |
| Contratação do parecer de custódia | antes de liberar convidados na mainnet (recomendado) |
| Criação da conta AWS | antes do Infrastructure Design |
