# Perguntas do desenho de infraestrutura — U7 Ledger e P&L

Contexto: o ambiente continua só local (Docker Compose no Windows; CI no GitHub Actions), como nas Units anteriores. O desenho não funcional está em [../nfr-design/](../nfr-design/) (revisão READY com duas observações a resolver aqui, em [review-02.md](../nfr-design/reviews/review-02.md)).

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **Observação 1 da revisão (ordem de travas do fechamento pendente):** o fechamento pendente trava `paper_account` → `position` → `pnl_daily` → `equity_peak`, igual à execução, e o teste de ordem das travas cobre esse caminho.
- **Observação 2 da revisão (cenário `u7-jobs`):** o `scripts/load-test.sh` ganha os cenários `u7` (10 minutos) e `u7-jobs` (30 minutos, 60 rodadas da marcação e do fechamento pendente com os ciclos rodando ao mesmo tempo).
- **Nenhum serviço novo no Compose:** a U7 roda dentro do processo `api` e usa o PostgreSQL existente.
- **Migração `0008-ledger-pnl`:** tabelas, índices, `CHECK`, gatilhos, privilégios da matriz e a política v1 semeada; nada existente é removido.
- **Tarefas:** marcação, fechamento pendente e reconciliação no agendador da `api`, com ShedLock; o pool do agendador ganha 3 linhas.
- **Sem segredo novo.**

## Q1. [CI/CD] Como o smoke de ponta a ponta da U7 obtém operações para mostrar?

A U5 (bots e ciclo de decisão), que cria as operações de verdade, ainda não foi construída. O smoke da U7 (Playwright) precisa de operações, posições e lançamentos para abrir a tela de P&L, o ledger e a reconciliação.

- A. Só no perfil `test`, um semeador cria para a conta de smoke da U1 uma conta PAPER com `FUNDING` e algumas operações, chamando a própria `PaperExecution` numa transação de teste (os mesmos cálculos de produção); o smoke confere P&L, ledger, "ver hipóteses" e "Reconciliado"
- B. O smoke da U7 só confere os estados vazios (sem operações) agora, e o fluxo com operações entra no smoke da U5
- X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
