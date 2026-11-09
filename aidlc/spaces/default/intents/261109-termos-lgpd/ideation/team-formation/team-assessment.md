# Avaliação do time — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/scope-definition/scope-document.md`, `intent-backlog.md`, `ideation/feasibility/feasibility-assessment.md`, `constraint-register.md` e as respostas de `team-formation-questions.md`.

## Disponibilidade

| Pessoa ou apoio | Papel | Capacidade | Local e fuso | Origem |
|---|---|---|---|---|
| Responsável do projeto | Executa e aprova todas as fases | A mesma do trabalho anterior; sem iniciativa concorrente, porque a revalidação anterior está pausada | Brasil | Q1, Q3 |
| Agentes de IA (produto, arquitetura, desenvolvimento, qualidade, operação) | Apoio em cada etapa | Sob demanda | — | Q1 |
| Assessoria jurídica que emitiu o parecer | Revê os textos finais (termos v2 e política) antes da publicação | Só no ponto de revisão do texto | — | Q2, Q4 |

## Volume de trabalho

Oito itens no backlog: seis Must, um Should e um Could (intent-backlog.md). O maior risco técnico está na exclusão por anonimização, C5 (feasibility-assessment.md).

## Acordo de capacidade

Sem data fixa; tudo antes da abertura do beta. O ritmo é o de um desenvolvedor solo (scope-document.md; TL-C06).

## RACI

| Atividade | Responsável do projeto | Agentes de IA | Assessoria jurídica |
|---|---|---|---|
| Requisitos e design | R, A | C | I |
| Textos dos termos e da política | R, A | C | C (revisão final) |
| Código e testes | R, A | C | — |
| Publicação da versão do beta | R, A | — | I |
| Atendimento de pedidos de titular | R, A | — | C quando houver dúvida |

R = executa; A = aprova; C = consultado; I = informado.

## Checklist de entrada

- Ambiente local funcionando (Docker, Java 21, Node e pnpm): já está pronto, porque a release `v2026.11.08-1` foi verificada.
- Parecer jurídico disponível para consulta durante a escrita dos textos.
