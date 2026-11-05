# Perguntas de requisitos não funcionais — U5 Bots e decisão

O design funcional da U5 está em [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md) e [frontend-components.md](../functional-design/frontend-components.md).

Requisitos da inception que tocam a U5:
- **NFR2:** 60 bots ativos; cada ciclo termina antes de o dado ficar desatualizado.
- **NFR3:** p95 ≤ 2 s nas telas com 20 usuários.
- **NFR4:** integridade.
- **NFR5:** isolamento.
- **NFR6:** segurança.
- **NFR8:** decisão reproduzível.
- **NFR9:** saúde, erro e correlação.
- **NFR10:** sem meta de disponibilidade no beta local.
- **NFR11:** acessibilidade básica.
- **NFR12:** cobertura e TDD no núcleo financeiro.
- **NFR13:** Windows e Linux.
- **NFR14:** pt-BR.

Os NFR2.2 e NFR3.2 da U6 ficaram para medir aqui: a barreira mais a execução com p95 ≤ 500 ms, e a tela do bot com p95 ≤ 2 s.

Já resolvido sem pergunta (pode corrigir na revisão do resumo):
- **TDD.** Os testes vêm antes do código nas regras de pausa, perda diária, meia-noite UTC e encerramento, e em todo o ciclo e na decisão (núcleo financeiro). O restante é testado depois de cada camada.
- **Processo worker.** A U5 cria o processo worker, que roda o ciclo, a meia-noite, a conferência do encerramento e o expurgo das decisões. A API roda os comandos, as telas e o canal C7.
- **Sem alertas automáticos** no beta local.
- **Sem dependência nova.**

## Q1. [Desempenho] Quanto tempo um ciclo de bot pode levar

O ciclo inteiro roda dentro de uma transação, com o bot travado: a decisão, a barreira da U6 (p95 ≤ 250 ms) e a execução da U7 (p95 ≤ 250 ms).

A. O ciclo inteiro tem p95 ≤ 1 s por bot, e os 60 bots ativos terminam a fatia em até 60 s depois do início.
B. O ciclo inteiro tem p95 ≤ 2 s por bot, e os 60 bots terminam em até 120 s.
X. Other (please specify)

[Answer]: B

## Q2. [Desempenho] Tempo das rotas de bots (C4)

A. p95 ≤ 500 ms em lista, rascunho, comandos, limites e decisões, com 20 usuários simultâneos.
B. p95 ≤ 1 s nas mesmas rotas, como nas rotas da U7.
X. Other (please specify)

[Answer]: B

## Q3. [Escala] Quantos ciclos rodam ao mesmo tempo no worker

A. Até 4 ciclos em paralelo, cada um com a sua conexão.
B. Até 8 ciclos em paralelo.
X. Other (please specify)

[Answer]: A

## Q4. [Segurança] Limite de requisições nos comandos dos bots

A. Iniciar, pausar, retomar, encerrar, editar limites e salvar rascunho: até 30 por minuto por pessoa no total. Acima disso, `429` com `Retry-After`.
B. Sem limite próprio, porque a idempotência e o limite de 3 bots ativos já contêm o abuso.
X. Other (please specify)

[Answer]: B

## Q5. [Observabilidade] Métricas próprias da U5

A. Contadores de ciclos por resultado (sem ordem, preenchida, não preenchida, recusada, pulado, falhou), um histograma da duração do ciclo e um contador de bots por estado. Nenhum rótulo leva pessoa, bot ou token.
B. Só a métrica de saúde e a de erro exigidas pelo NFR9, como na U6 e na U7.
X. Other (please specify)

[Answer]: B

## Q6. [Confiabilidade] Saúde do worker

A. O indicador do worker fica `DOWN` quando nenhuma fatia termina há mais de 10 minutos e há bots ativos, e entra na prontidão do worker. O Compose reinicia o worker nesse caso.
B. O indicador é só informativo, como o da U6: aparece no detalhe da saúde sem reiniciar nada.
X. Other (please specify)

[Answer]: B

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
