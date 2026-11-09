# Avaliação de viabilidade — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/market-research/competitive-analysis.md`, `market-trends.md`, `build-vs-buy.md` e as respostas de `feasibility-questions.md`.

## Veredito

**Viável**, sem dependência externa nova, com o que o produto já tem (Q1, Q6). Um único ponto depende de decisão futura: o local de armazenamento dos dados, que só é conhecido quando houver hospedagem (Q3).

## Viabilidade técnica

| Parte | O que já existe | O que falta | Viabilidade |
|---|---|---|---|
| Termos v2 e política | Catálogo versionado da U3 com aceite por versão e novo aceite quando a versão muda (`TERMS_NOT_ACCEPTED`) | Texto definitivo dos termos e da política, como nova versão do catálogo | Alta (Q1) |
| Páginas públicas `/termos` e `/privacidade` | Apps Next.js do convidado e do administrador | Páginas sem login, links no rodapé e no convite | Alta (Q1) |
| Pedidos de titular | App do administrador (U8) e trilha de auditoria (U2) | Formulário na tela Conta, registro com andamento e datas, tela de atendimento no administrador | Média: é funcionalidade nova, mas pequena (Q5) |
| Exclusão por anonimização | Ledger append-only, auditoria com identificadores internos | Rotina que desativa a conta, revoga sessões e troca ou apaga os dados que identificam a pessoa, sem tocar no ledger | Média: precisa de cuidado com as tabelas de cada módulo e testes de isolamento (Q2) |
| Tabela de retenção | Expurgos de auditoria, feedback, avaliações de risco e histórico de mercado | Conferir cada dado com seu expurgo ou regra de guarda, e testes | Alta (Q1) |
| Contato do encarregado | — | E-mail configurado na implantação, fora do código (Q5) | Alta |

## Riscos principais

| Risco | Impacto | Mitigação |
|---|---|---|
| A anonimização deixa um dado que ainda identifica a pessoa (por exemplo, a carteira num log ou num evento antigo) | Alto: o pedido de exclusão não seria cumprido | Inventário de onde cada dado pessoal mora; teste que procura a carteira depois da anonimização; a varredura de logs já existente continua (Q2) |
| A política descreve algo diferente do que o sistema faz | Alto: transparência falsa | Tabela de retenção ligada aos expurgos reais, com testes (Q1, Q3) |
| Local de armazenamento desconhecido até haver hospedagem | Médio: bloqueia a publicação da versão do beta | Campo da política preenchido na implantação; a publicação espera a hospedagem (Q3) |
| Prazo de 15 dias para responder o pedido depende de uma pessoa | Médio | Pedido com data de recebimento e alerta de prazo no app do administrador (Q5) |

## Equipe e prazo

Desenvolvedor solo, stack atual, sem orçamento extra; antes da abertura do beta, sem data fixa (Q4).
