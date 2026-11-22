# Plano das histórias — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes: `inception/requirements-analysis/requirements.md` (FR1 a FR8 e NFR1 a NFR8), `ideation/intent-capture/stakeholder-map.md` e `ideation/rough-mockups/`.

As histórias seguem o INVEST: independentes, negociáveis, com valor, estimáveis, pequenas e testáveis. Os critérios de aceite usam Dado/Quando/Então. A prioridade segue o MoSCoW do escopo.

## Q1. Personas

Quem são as personas?

A. Duas personas (recomendado):
   - **Convidado do beta:** testa estratégias no simulado e passa para o real com valores pequenos. Quer comparar os resultados com clareza.
   - **Fundador:** usa o produto como convidado, testa na devnet e confere que o P&L bate com o ledger.
B. Uma persona só (convidado)
X. Other (please specify)

[Answer]: A

## Q2. Como quebrar as histórias

Como as histórias são divididas?

A. Por fluxo do usuário, em grupos que seguem os requisitos (recomendado):
   - US1: criar bot no modo;
   - US2: lista única;
   - US3: P&L por bot e rateio;
   - US4: totais por modo e aviso;
   - US5: correção do P&L REAL e conferência com o ledger;
   - US6: venda só da própria quantidade;
   - US7: períodos;
   - US8: comparação.
B. Por persona
X. Other (please specify)

[Answer]: A

## Q3. Tamanho das histórias

Qual o tamanho de cada história?

A. Pequenas: uma história por comportamento que pode ser testado sozinho. As regras de dinheiro do FR3.7 a FR3.11 viram critérios de aceite com valores exatos em unidades base, e não histórias separadas. (recomendado)
B. Uma história grande por requisito
X. Other (please specify)

[Answer]: A

## Q4. Prioridade

Como fica a prioridade das histórias?

A. Igual ao escopo e aos requisitos:
   - **Must:** US1 a US6;
   - **Should:** o filtro (FR2.3), o detalhe (FR3.6) e os períodos (FR7);
   - **Could:** a comparação (FR8).

   O FR6 é Must, mas fica marcado como "depois da Performance Validation do REAL". (recomendado)
B. Tudo Must
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Duas personas: o convidado do beta, que testa no simulado e passa para o real com valores pequenos, e o fundador, que testa na devnet e confere o P&L com o ledger [Q1].
- Histórias organizadas por fluxo, em oito grupos que seguem os requisitos: US1 criar, US2 lista, US3 P&L por bot e rateio, US4 totais e aviso, US5 correção e conferência, US6 venda só da própria quantidade, US7 períodos, US8 comparação [Q2].
- Histórias pequenas, que podem ser testadas sozinhas. As regras de dinheiro entram como critérios de aceite com valores exatos em unidades base [Q3].
- Prioridade igual à do escopo: Must para US1 a US6, sendo que a US6 só entra depois da Performance Validation do REAL; Should para o filtro, o detalhe e os períodos; Could para a comparação [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
