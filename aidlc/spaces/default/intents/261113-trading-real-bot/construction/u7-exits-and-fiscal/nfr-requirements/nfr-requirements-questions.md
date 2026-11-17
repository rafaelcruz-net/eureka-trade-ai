# Perguntas de NFR Requirements — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Base:
- `construction/u7-exits-and-fiscal/functional-design/` (BR0 a BR9, S1 a S50, adendo de contrato);
- as NFRs da U4 e da U5 (padrões de métricas, alertas, paridade com o Terraform e testes de privilégios);
- `team.md`: testes antes do código para a política do TxSigner, o ledger e o saque, e piso de 80%;
- `project.md`: sem ponto flutuante, sem SDK Solana em produção, saque sempre disponível, tenant do token.

## Q1. [Tempos] Que metas valem?

A. Quatro metas:
- **Preenchimento:** entra no ledger em até 90 s depois de `finalized` na rede (ciclo de 30 s mais a conferência).
- **Kill switch:** todas as condicionais `OPEN` ficam `CANCELLING` em até 2 min, e o cancelamento é confirmado em até 5 min com a rede saudável.
- **Exportação:** a de 1 ano, com até 20 mil linhas, sai em até 10 s, em fluxo, sem carregar tudo na memória.
- **Criação até a sessão de assinatura:** p95 de 3 s.

São metas medidas em teste de integração com relógio fixo e respostas gravadas, sem portão de carga.
B. Sem metas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Segurança] O que é obrigatório?

A. Cinco obrigações, cada uma com teste:
1. **`TriggerPolicy`:**
   - um teste de ataque por motivo de BR2.4, escrito antes do código, sobre as transações gravadas;
   - na ausência da gravação, o TxSigner recusa (`POLICY_NOT_RECORDED`).
2. **Limites de preço (BR2.6):** testados na API.
3. **Chave de API da Jupiter:** num segredo só do worker, nunca em log nem no navegador.
4. **CSV:**
   - só os dados da pessoa (teste de isolamento);
   - proteção contra fórmula;
   - sem dado de outra pessoa nem do PAPER;
   - nenhum endereço completo da carteira principal (só a carteira do bot, que é da pessoa).
5. **Regras de arquitetura (ArchUnit):** rede só no adaptador da Jupiter e no RPC; nenhuma assinatura fora do TxSigner.
B. Só boas práticas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Confiabilidade] Como garantir que nada vende duas vezes nem fica preso?

A. Cinco garantias:
- **Estado:** cada mudança é compare-and-set.
- **Lançamento:** o preenchimento é idempotente pela assinatura e gravado na mesma transação do `FILLED`.
- **Venda a mercado:** só segue depois de `CANCELLED` ou `FILLED` e da releitura da posição no ledger (BR4.3).
- **Trabalhos:** leitura de 30 s e cancelamento de 1 min, com ShedLock, retomando do estado gravado.
- **Cancelamento:** novo a cada minuto até confirmar.

Os testes de integração com PostgreSQL real cobrem as corridas:
- preenchimento contra cancelamento;
- venda a mercado contra preenchimento;
- dois workers.

O saque nunca depende de nenhum trabalho da U7.
B. Confiar na Jupiter
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Observabilidade] Que métricas e alertas?

A. Métricas com rótulos fechados e contadores começando em zero, no padrão da U4 e da U5:
- condicionais por estado;
- preenchimentos;
- cancelamentos por motivo;
- recusas da `TriggerPolicy` por motivo;
- tempo do preenchimento até o ledger;
- exportações e recusas por limite;
- batimento de cada trabalho.

Alertas:
- condicional em `CANCELLING` há mais de 15 min (crítico);
- condicional `OPEN` sem leitura há mais de 2 h (alto);
- trabalho parado;
- recusa da `TriggerPolicy` (alto, porque indica servidor fora da política).

As regras ficam no Prometheus e no CloudWatch, com teste de paridade.
B. Só logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Escala e retenção] O que vale?

A. Piloto de até 21 carteiras, com no máximo uma condicional por posição. Índices por (carteira, estado) e por (pessoa, criação).

As condicionais finais ficam 5 anos, como as ordens REAL (prazo fiscal), com expurgo pelo pool de retenção que já existe.

A exportação lê o ledger por pessoa e período com o índice que já existe e nunca carrega mais de 1 ano.
B. Guardar para sempre
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Testes] O que cada camada prova?

A. A ordem do `team.md`:
- **Testes antes do código:**
  - a `TriggerPolicy` (depois da gravação);
  - os estados e a idempotência das condicionais;
  - o lançamento do preenchimento;
  - a prevenção de venda dupla;
  - o CSV com a conferência de BR7.6.
- **Depois:** rotas, telas, métricas e o adaptador.

Ponta a ponta no validador local:
- spec `e2e/real-exports` (exportação do histórico depois do `real-swap`, conferindo colunas, sinais e a linha da compra);
- condicionais pela tela com o adaptador simulado, sem transação, cobrindo criar, preencher simulado, cancelar pelo kill switch e o selo de estado.

Com a gravação real, um spec da condicional no validador entra no lugar do simulado. Piso de 80% na API, no web e no TxSigner.
B. Só testes de unidade
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Tecnologia] Alguma dependência nova?

A. Nenhuma de produção:
- o CSV é escrito por um gravador próprio (sem biblioteca), com o BOM e o escape de BR7;
- o adaptador da Jupiter usa o `RestClient` que já existe;
- a política do TxSigner usa o leitor de transação que já existe.

A gravação real estende o `record.sh` (ferramentas da U4), e o SDK Solana continua só no escopo de teste.
B. Biblioteca de CSV
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Preenchimento no ledger em até 90 s, kill switch cancelando em até 2 min e confirmando em até 5, exportação de 1 ano em até 10 s em fluxo, criação com p95 de 3 s [Q1].
- Política do TxSigner com ataques antes do código, limites de preço, chave da Jupiter só no worker, CSV isolado e protegido, regras de arquitetura [Q2].
- Compare-and-set, lançamento idempotente na mesma transação, venda a mercado depois do cancelamento, trabalhos com ShedLock e testes das corridas; saque independente [Q3].
- Métricas fechadas e alertas de cancelamento parado, leitura parada, trabalho parado e recusa da política, com paridade [Q4].
- Piloto de 21 carteiras, índices, retenção de 5 anos e exportação limitada a 1 ano [Q5].
- Testes antes do código nas partes de dinheiro e política; e2e de exportação e de condicionais com o simulado; piso de 80% [Q6].
- Nenhuma dependência nova de produção; CSV próprio; SDK só em teste [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
