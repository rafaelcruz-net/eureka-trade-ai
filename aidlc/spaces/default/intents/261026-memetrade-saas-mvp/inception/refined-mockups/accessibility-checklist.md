# Checklist de acessibilidade — MemeTrade Analytics (beta)

Nível do beta: acessibilidade básica (NFR11 de [requirements.md](../requirements-analysis/requirements.md); correção de acessibilidade em `project.md`); WCAG 2.1 AA completo antes de abrir ao público. Sem portão automático na CI por enquanto ([team-practices.md](../practices-discovery/team-practices.md)). Cada item liga aos critérios CT-UX de [stories.md](../user-stories/stories.md) e às telas de [mockups.md](mockups.md), que partem dos [wireframes](../../ideation/rough-mockups/wireframes.md) e [fluxos](../../ideation/rough-mockups/user-flow.md).

## Obrigatório no beta

| # | Item | Telas | Critério | Como verificar |
|---|---|---|---|---|
| 1 | Todo o fluxo principal funciona só com teclado, sem armadilha de foco | Todas | CT-UX-4 | Percorrer F1 a F5 sem mouse |
| 2 | Foco sempre visível (anel de 3:1) | Todas | CT-UX-4 | Inspeção visual com Tab |
| 3 | Foco inicial de cada tela segue o mockup | Todas | CT-UX-4 | Abrir cada tela e verificar o foco |
| 4 | Campos com rótulo visível, unidade e erro em texto ligado ao campo | S4, S9, A2 | CT-UX-6 | Leitor de tela anuncia rótulo e erro |
| 5 | Cor nunca é o único indicador (risco, ganho/perda, estado, desatualizado) | S2, S3, S5, S6, S8 | CT-UX-7 | Ver em escala de cinza |
| 6 | Contraste de texto ≥ 4,5:1 no tema escuro | Todas | CT-UX-7 | Medir os tokens de cor |
| 7 | Diálogos prendem o foco, fecham com Esc e devolvem o foco | S5, S9, A1, A2 | CT-UX-5 | Abrir e fechar por teclado |
| 8 | Atualizações automáticas anunciadas sem mover o foco | S2, S3, S5 | CT-UX-8 | Leitor de tela com dados mudando |
| 9 | Cada tela com h1 e regiões nomeadas | Todas | CT-UX-9 | Navegar por títulos e regiões |
| 10 | Termos em inglês com explicação por mouse e foco | S3, S4, S6 | CT-UX-1 | Focar no termo |
| 11 | Tabelas com cabeçalhos e ordenação anunciada | S3, S5, S6, S8, A2 | Interação | Leitor de tela em modo tabela |
| 12 | Página em pt-BR declarada | Todas | NFR14 | Inspeção do documento |

## Antes de abrir ao público (WCAG 2.1 AA completo)

| # | Item | Observação |
|---|---|---|
| 13 | Contraste de componentes e foco ≥ 3:1 em todos os estados | Hoje só o anel de foco é exigido |
| 14 | Zoom de 200% sem perda de função | A largura mínima de 1280 px precisa ser revista |
| 15 | Reflow e redimensionamento de texto | Depende da revisão de largura mínima |
| 16 | Movimento reduzido respeitado | Animações mínimas já previstas |
| 17 | Teste com leitores de tela (NVDA no Windows) e auditoria automatizada | Hoje não há portão na CI |

## Pendências e riscos

- A largura mínima de 1280 px conflita com zoom de 200% em telas comuns; aceitável no beta e obrigatório de revisar antes do público.
- Os valores dos tokens de cor são propostos; o contraste precisa ser medido antes da implementação (premissa A3 dos requisitos).
