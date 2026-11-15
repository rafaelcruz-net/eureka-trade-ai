# Resumo da iniciativa — Trading REAL

Conversa: português (Brasil). Compila `ideation/intent-capture/intent-statement.md`, `ideation/intent-capture/stakeholder-map.md`, `ideation/feasibility/feasibility-assessment.md`, `ideation/feasibility/constraint-register.md`, `ideation/feasibility/raid-log.md`, `ideation/scope-definition/scope-document.md`, `ideation/scope-definition/intent-backlog.md` e `approval-handoff-questions.md` (Q1 a Q6).

## Intenção e problema

O produto só opera em PAPER. A iniciativa permite que o bot opere com dinheiro real na Solana: o modo Assinada é o padrão, e o modo Automática total, pela carteira do bot com teto, é o foco. Cada usuário tem uma carteira do bot gerada pelo sistema, com a chave no KMS, depósito até o teto e saque só para a carteira principal. O risco é limitado por teto, limites por ordem e por dia e kill switch (intent-statement).

## Validação de mercado

A pesquisa do intent `261026-memetrade-saas-mvp` cobre o mercado de scanners e bots na Solana; o REAL é uma função nova do mesmo produto, e a pesquisa anterior basta (Q4).

## Viabilidade e riscos

- **Veredito:** viável com condições (feasibility-assessment).
- **Condições:** o KMS assina Ed25519; um swap da Jupiter roda num validador local com contas da mainnet clonadas, porque a API de swap só opera na mainnet; a CoinGecko paga está contratada.
- **Exposição:** teto máximo de 10 SOL para o fundador e para cada convidado, configurável no perfil do convidado; pior caso de 210 SOL no piloto (constraint-register TR-C11, TR-C12).
- **Riscos aceitos pelo fundador** (Q2): comprometimento das chaves, KMS sem Ed25519, bug sem auditoria externa nem segundo revisor, enquadramento como custódia, derrapagem e duplicação de ordens, divergência do ledger, queda do RPC ou da Jupiter; cada um com mitigação no `raid-log.md`. O fundador segue sem auditoria externa e sem parecer obrigatório; o parecer de custódia continua recomendado antes dos convidados.

## Escopo

| Dentro (Must) | Logo depois (Should) | Fora |
|---|---|---|
| Dados reais, carteira do bot, executor Jupiter, ledger REAL e reconciliação, barreira REAL, modos e termos, modo Assinada, liberação controlada | Automática nas saídas, exportação fiscal | Vault on-chain e G3, público aberto e cobrança, outras redes e roteadores, alavancagem e derivativos |

Fonte: scope-document e intent-backlog.

## Visual

Não houve mockups rascunho nesta intent; as telas partem das existentes e são refinadas na Inception (Q5).

## Plano de equipe e recursos

- **Equipe:** o fundador sozinho, com os agentes do AI-DLC, sem mob; ele decide e é o único revisor (stakeholder-map, Q5).
- **Orçamento:** até US$ 100 por mês no piloto e o tempo do fundador; a conta AWS é criada antes do desenho de infraestrutura (Q3).
- **Prazo:** sem data fixa; 3 a 6 semanas de trabalho técnico estimadas.

## Sequência

Fase 0 (provas técnicas) → Fase 1 (barreira, dados reais, carteira do bot) → Fase 2 (executor, ledger REAL) → Fase 3 (modos e termos, Assinada, liberação) → Fase 4 (exportação fiscal, saídas automáticas). Liberação: devnet, depois a carteira do fundador, depois até 20 convidados, cada passo após 24 horas com tudo verde (intent-backlog, intent-statement).

## Regras do projeto a mudar antes da Construction

O REAL automático contradiz regras vigentes de `project.md` e `team.md`; a mudança é feita pelo registro do AI-DLC, com aprovação do fundador, e o G2 (modelo de ameaças) é cumprido no Domain Design (feasibility-assessment).

## Recomendação

**Seguir (go)** para a Inception, com as três provas técnicas como primeira entrega e condição para continuar (Q6).
