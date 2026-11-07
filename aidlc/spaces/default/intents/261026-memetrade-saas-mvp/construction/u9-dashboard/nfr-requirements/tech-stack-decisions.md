# Decisões de tecnologia — U9 Painel e navegação

Base: [respostas](nfr-requirements-questions.md) Q4 e Q5, [componentes](../functional-design/frontend-components.md), [especificação funcional](../functional-design/functional-spec.md) e o `team.md`. A stack do app do convidado já está fixada pelas Units anteriores (U1, U3, U4, U5, U7); esta Unit não acrescenta dependência.

## Decisões

| Área | Escolha | Motivo | Alternativa rejeitada |
|---|---|---|---|
| Framework | Next.js 15 e React 19 já usados no `apps/web` | Mesma base das outras telas | — |
| Leitura do canal | Leitor próprio do `text/event-stream` sobre `fetch` e `ReadableStream`, em TypeScript `strict`, sem dependência nova | Q4: o canal exige cabeçalho `Authorization`, o `EventSource` não serve (`project.md`), e o `team.md` pede espera para pacotes novos; o formato é pequeno e fácil de testar | Biblioteca pronta de eventos sobre `fetch` (mais uma dependência na cadeia de suprimentos para pouco código) |
| Tipos dos eventos | Gerados do `openapi-guest.yaml` por `openapi-typescript` (`pnpm gen:api`), depois que a geração de código acrescentar os esquemas dos envelopes de C7 | Tipos nunca escritos à mão (`team.md`) | Tipos à mão no cliente |
| Estado do painel | Redutor único do React (`useReducer`) com contexto, sem biblioteca de estado | REST e canal passam pelo mesmo caminho (BR1.4); o estado é de uma tela só | Biblioteca de cache de consultas (dependência nova e duas fontes de verdade com o canal) |
| Formatação decimal | Funções decimais já usadas pela tela S6 (U7) | Sem ponto flutuante (`project.md`) | Formatação numérica do navegador sobre `Number` |
| Testes de unidade e componente | Jest e Testing Library já configurados, com relógio falso | Cobertura de 80% medida pelo Jest (`team.md`); determinismo | — |
| Ponta a ponta | Playwright já configurado, com a carteira de teste do `e2e/support` | Q5: pausa em segunda aba prova a atualização sem recarregar de forma determinística | Esperar uma ordem nova do ciclo (pode dar `NO_TRADE`, teste instável) |
| Teste de carga das metas de NFR3 e NFR1 | Perfil `loadtest` do backend, que já tem sementes e acionadores em Java das Units anteriores (`bots/loadtest`, `ledger/loadtest`), mais um cliente de teste que abre as rotas do painel e o canal pelas 20 sessões, só no ambiente local; a ferramenta exata do cliente fica para a NFR Design, sem dependência nova no `apps/web` | Mede p95 por quadro e o atraso do canal reaproveitando o que existe | Ferramenta de carga nova no app web |

## O que fica para a NFR Design

- Confirmar ou retirar o teto de 5 canais por pessoa no servidor (NFR2.1), que é do canal da U5.
- Detalhar o leitor do `text/event-stream` (tamanho máximo de evento, comportamento com linha muito longa).
- Medir se a busca da última ordem em todos os bots não rascunho cabe na meta de NFR3.1 com 20 encerrados por pessoa.
