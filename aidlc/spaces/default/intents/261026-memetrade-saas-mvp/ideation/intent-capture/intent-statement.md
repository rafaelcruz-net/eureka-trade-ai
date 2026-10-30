# Definição da iniciativa — MemeTrade Analytics

## Sources
- [desc] Initial description: "Leia o arquivo PromptInicial.txt e utilize o aidlc para executar o PRD"
- [scope] Workflow-selected scope: `enterprise`.

## Problem Statement
Validar primeiro a descoberta e a avaliação de tokens para traders de Solana. A automação integra um momento posterior da construção; a primeira disponibilização aos convidados reúne descoberta, avaliação, bots PAPER e acompanhamento de lucros e perdas (P&L). A prioridade de construção e o conteúdo da primeira disponibilização são decisões distintas e compatíveis. [Q1] [Q5]

## Target Customer
Traders convidados no Brasil, já familiarizados com Solana e carteiras digitais. A validação será feita com esse público, que contribuirá com feedback sobre o produto. [Q2] [Q6]

## Success Metrics
O critério inicial de sucesso é integridade; metas de adoção serão definidas após discovery. Cada critério abaixo se aplica quando a respectiva capacidade estiver disponível. [Q3]

| Resultado esperado | Evidência a avaliar | Momento de aplicação | Fonte |
|---|---|---|---|
| Dados com origem e atualização identificadas | Verificar a presença da origem e do momento de atualização nos dados apresentados | Descoberta e avaliação | [Q3] |
| Operações sem duplicidade | Verificar que uma mesma operação não aparece como operações independentes repetidas | Bots PAPER; posteriormente REAL | [Q3] [Q5] |
| Respeito aos limites estabelecidos | Comparar operações com os limites autorizados e identificar violações | Bots PAPER; posteriormente REAL | [Q3] [Q5] |
| P&L reconciliável | Confrontar resultados apresentados com os registros das operações correspondentes | Primeira disponibilização com PAPER | [Q3] [Q5] |
| Metas de uso definidas após discovery | Registrar metas de uso quando forem decididas; nenhuma quantidade de usuários ou meta de retenção foi fixada nesta etapa | Após discovery | [Q3] |

## Initiative Trigger
Explorar a oportunidade de produto, sem uma data externa obrigatória. As estimativas serão revistas após a Sprint 0. Essa decisão não equivale a um compromisso de data de lançamento. [Q4]

## Initial Scope Signal
Plano de trabalho **enterprise**, classificado como **workflow-selected**. Essa escolha organiza o trabalho; os limites do produto são os confirmados abaixo. [scope] [Q5]

| Marco | Conteúdo confirmado | Condição ou limite | Fonte |
|---|---|---|---|
| Análise e planejamento | Primeira entrega documental antes de implementar código | Priorizar Sprint 0 e viabilidade da delegação; aprovação por etapa | [Q7] |
| Prioridade de construção | Descoberta e avaliação de tokens | Precedem a automação na construção | [Q1] [Q5] |
| Primeira disponibilização aos convidados | Descoberta, avaliação, bots PAPER e P&L | Público convidado no Brasil | [Q2] [Q5] |
| Evolução REAL | Trading real em momento posterior | Condicionado à validação de delegação e revogação, auditoria e avaliação jurídica | [Q5] |
| Cobrança pública | Momento posterior | Não integra a primeira disponibilização | [Q5] |

Ficam fora da primeira versão: alavancagem, derivativos, outras blockchains, copy trading, custódia centralizada, saques administrados pela plataforma e manipulação de mercado. [Q5]

## Decisões de gestão e dependências
O responsável pelo projeto decide prioridades e aprova entregas. O desenvolvimento será conduzido por ele com apoio de IA. As comunicações serão em português, com documentos no repositório e resumos no chat, mantendo aprovação por etapa. [Q6] [Q7] [Q8]

| Definição ainda pendente, explicitamente reconhecida | Situação confirmada | Fonte |
|---|---|---|
| Metas de uso | Serão definidas após discovery | [Q3] |
| Estimativas | Serão revistas após Sprint 0; sem data obrigatória | [Q4] |
| Responsáveis humanos por operações, segurança, jurídico e auditoria externa | Ainda serão definidos | [Q6] |
| Orçamento de provedores, auditoria externa e assessoria jurídica | Ainda será definido; sem contratação nesta etapa | [Q8] |

## Assumptions & Open Questions
None.
