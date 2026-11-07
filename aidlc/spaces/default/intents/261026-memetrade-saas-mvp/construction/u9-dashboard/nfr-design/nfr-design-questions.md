# Perguntas do design não funcional — U9 Painel e navegação

Unit `u9-dashboard` (kind `ui`). Base: [performance-requirements.md](../nfr-requirements/performance-requirements.md), [security-requirements.md](../nfr-requirements/security-requirements.md), [tech-stack-decisions.md](../nfr-requirements/tech-stack-decisions.md), [functional-spec.md](../functional-design/functional-spec.md) e o canal C7 de `contract-summary.md`.

Já decidido, sem pergunta (seguindo o que as Units anteriores já fazem e as pendências deixadas pela revisão dos requisitos):
- A proibição de `EventSource` vira regra de lint (`no-restricted-globals` no `eslint.config.mjs`, que já usa essa regra), no lugar de busca de texto, para não pegar comentários nem testes.
- O critério de armazenamento procura só o token (chave ou valor) em `localStorage`, `sessionStorage` e IndexedDB, porque a U3 usa `sessionStorage` para outra coisa.
- A abertura e a reabertura do canal mandam `X-Correlation-Id`, como o cliente REST já faz.
- As medições de NFR3.1 e NFR1.1 seguem o padrão das Units anteriores: testes de integração Java com medição (como `AdminReadLatencyIT` e `KillSwitchPerformanceIT`), com PostgreSQL em contêiner e os acionadores do perfil `loadtest` para gravar ordens; a mudança de estado de bot é gravada pela rota de comando.
- A última ordem de um bot encerrado é buscada uma vez por abertura do painel e reaproveitada nas cargas seguintes, porque não muda depois do encerramento.

## Q1. [Segurança] O teto de canais por pessoa existe ou sai?

A revisão dos requisitos mostrou que o canal da U5 (`DashboardMarkPoller.open`) não conta canais por pessoa. O NFR2.1 propôs 5 canais por pessoa para que uma conta não esgote o servidor. A U9 é só interface; aplicar o teto mexe no código do canal da U5, que é pequeno e isolado.

A. Criar o teto na geração de código da U9, dentro do canal da U5: o 6º canal aberto da mesma pessoa recebe `429 RATE_LIMITED` com `Retry-After: 30` em `ProblemDetail`, antes de abrir o fluxo; o acréscimo fica registrado em C7 no `contract-summary.md`; um teste de integração abre 6 canais e confere a recusa e que fechar um libera a vaga. O app trata o `429` como queda (espera e tenta de novo).
B. Retirar o teto: com 20 convidados no beta, a espera crescente do app e o custo por canal (só ler as marcas das pessoas com canal aberto) bastam; a ameaça fica registrada como risco aceito até haver hospedagem.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1: o teto de 5 canais por pessoa é criado na geração de código da U9, dentro do canal da U5; o 6º recebe `429 RATE_LIMITED` com `Retry-After: 30`, o acréscimo fica registrado em C7 e um teste com 6 canais confere a recusa e a liberação da vaga; o app trata o `429` como queda.
- Sem pergunta: `EventSource` proibido por regra de lint; critério de armazenamento só para o token; `X-Correlation-Id` na abertura do canal; medições por testes de integração Java com o perfil `loadtest`; última ordem de bot encerrado buscada uma vez por abertura.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
