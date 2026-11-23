# Perguntas do NFR Requirements — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/u2-real-bot-pnl/functional-design/` (BR1.1 a BR10.5, incluindo D1 a D3 do fundador);
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR8);
- `construction/u1-mode-skeleton/nfr-requirements/`, que serve de modelo;
- `team.md` (Testing Posture);
- o estado do intent (Test Strategy `Standard`).

## Q1. Desempenho do P&L por bot e da barreira

Quais limites de tempo valem para a U2?

A. Três limites:
   - **C4 por modo:** p95 ≤ 1 s para uma pessoa com 10 bots e 2 anos de histórico, com o rateio completo. O portão fica no cenário de carga da CI, e o texto pronto do `load-test.yml` vai para o responsável.
   - **Leitura de atribuição (D1):** p95 ≤ 250 ms no caminho da barreira e do ciclo, medido sob a trava da carteira, e ≤ 800 ms no caminho do P&L.
   - **Barreira REAL (caminho só de quantidade):** p95 igual ou menor que o atual, com tolerância de +10%, medido no teste de integração.

   (recomendado)
B. Só o C4
X. Other (please specify)

[Answer]: A

## Q2. Exatidão e testes

Que testes garantem que as contas estão certas?

A. São estes:
   - os testes do núcleo financeiro são escritos antes do código: cenários do FR5.4 e todos os exemplos das histórias em unidades base;
   - um teste de propriedade com sementes fixas (pelo menos 1.000 sequências por execução) confere a igualdade exata com o ledger em qualquer período e o rateio sem lamport perdido;
   - um teste de paridade com três oráculos independentes;
   - nenhum ponto flutuante, conferido por uma regra ArchUnit que já existe;
   - a Test Strategy `Standard` e o piso de 80%.

   (recomendado)
B. Só cenários fixos
X. Other (please specify)

[Answer]: A

## Q3. Segurança e dados

Que regras de segurança valem para a U2?

A. São estas:
   - o tenant vem sempre da autenticação, e as leituras de ordens e de bots filtram pela pessoa;
   - `real_entry_slot` é só de inserção, como o ledger, e recusa UPDATE, DELETE e TRUNCATE;
   - o papel da aplicação só pode ler e inserir;
   - nenhum endereço de carteira ou assinatura completa aparece nos logs (assinatura abreviada);
   - a auditoria registra `RECONCILIATION_MISMATCH`, `UNKNOWN_BOT` e `REAL_SLOT_CONFLICT` sem dado pessoal.

   (recomendado)
B. Sem requisito novo
X. Other (please specify)

[Answer]: A

## Q4. Confiabilidade

Como a U2 se comporta quando algo falha?

A. Assim:
   - uma falha de atribuição nunca bloqueia venda, liquidação ou saque, e só recusa compra;
   - uma divergência com o ledger deixa o bloco REAL `UNAVAILABLE`, sem número parcial;
   - um conflito de slot nunca derruba a confirmação;
   - a migração da tabela nova é aditiva e o rollback devolve só a imagem;
   - o ponto de controle interno é cumprido quando o slot, o `RealCostMethod`, o `RealCostBasis` e a paridade estão verdes.

   (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. Observabilidade

O que a U2 passa a medir e a mostrar?

A. Métricas novas, sem dado pessoal:
   - divergências com o ledger, por motivo;
   - `SLOT_MISSING` lido;
   - conflitos de slot;
   - falhas e tempo da leitura de atribuição, por caminho.

   Entra um alarme novo: qualquer `RECONCILIATION_MISMATCH` em 240 s (duas leituras de 120 s). Isso indica dinheiro calculado errado, e por isso ele entra na lista do CloudWatch. (recomendado)
B. Só logs
X. Other (please specify)

[Answer]: A

## Q6. Escala e pilha

O que muda em escala e tecnologia?

A. Nada muda. A migração 0023 cria a tabela `real_entry_slot` e o índice com o slot, e a leitura em fluxo é feita por pessoa. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Desempenho:** C4 com p95 ≤ 1 s no teste de carga da CI, com o texto do `load-test.yml` para o responsável. Leitura de atribuição ≤ 250 ms na barreira e ≤ 800 ms no P&L. A barreira fica no máximo 10% mais lenta que hoje [Q1].
- **Exatidão:** testes antes do código com os cenários e exemplos; teste de propriedade com 1.000 sequências ou mais e sementes fixas; paridade com três oráculos; nenhum ponto flutuante; Standard e piso de 80% [Q2].
- **Segurança:** tenant vindo da autenticação; `real_entry_slot` só de inserção, e o app só lê e insere; nenhuma carteira ou assinatura completa em log; auditoria sem dado pessoal [Q3].
- **Confiabilidade:** a falha de atribuição só recusa compra; a divergência deixa o bloco indisponível; o conflito de slot não derruba a confirmação; migração aditiva; ponto de controle interno [Q4].
- **Observabilidade:** métricas novas e um alarme novo para qualquer divergência com o ledger, com janela de 240 s [Q5].
- **Escala:** nada muda, a não ser a migração 0023 com a tabela e o índice [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
