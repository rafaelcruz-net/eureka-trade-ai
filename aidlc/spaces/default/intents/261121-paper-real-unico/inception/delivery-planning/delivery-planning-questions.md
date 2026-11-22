# Perguntas do Delivery Planning — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho que termina em algo que roda.

## Fontes

- `inception/units-generation/`: U1 a U4, o grafo U1 → U2 → U3 e U4, o ponto de controle interno da U2 e os riscos RK-1 a RK-8.
- `inception/contract-design/contract-summary.md`: os contratos C1 a C17 e as perguntas PQ-6 a PQ-10.
- `inception/requirements-analysis/requirements.md`: a ordem de entrega com o risco primeiro.
- O `team.md` (Way of Working, Walking Skeleton e Deployment) e o estado deste intent: pontos de controle ligados, uma Unit por vez e execução em série.

## Q1. O que construir primeiro

O que entra primeiro na construção?

A. Primeiro o **esqueleto andante**, a menor fatia de ponta a ponta que funciona de verdade: a U1, que cria os dois bots e mostra os dois blocos sem somar. Depois o risco, com a U2, o núcleo financeiro. Em seguida o valor da tela, com a U3. Por último a U4, que depende da Performance Validation do intent 261113. (recomendado)
B. Valor primeiro (telas antes do cálculo)
X. Other (please specify)

[Answer]: A

## Q2. Modelo de pontuação

Como decidir a ordem entre as Units?

A. Usar o **WSJF**, que ordena pelo valor, pela urgência e pela redução de risco divididos pelo tamanho, só para ordenar U3 e U4, as duas que podem andar juntas. A ordem U1 → U2 é fixada pelo esqueleto e pelo grafo. Os pesos são iguais para valor, urgência e risco, como nos intents anteriores. (recomendado)
B. Sem pontuação
X. Other (please specify)

[Answer]: A

## Q3. Tamanho de cada Bolt

Quantas Units cabem em um Bolt?

A. **Um Bolt por Unit**: B1 = U1, B2 = U2, B3 = U3 e B4 = U4. A U2 tem um ponto de controle interno, com o slot, o custo médio único e a paridade verde. Se esse ponto atrasar, a U2 é dividida. (recomendado)
B. U3 e U4 juntas num Bolt
X. Other (please specify)

[Answer]: A

## Q4. Em série ou em paralelo

Os Bolts andam um de cada vez ou ao mesmo tempo?

A. **Em série**, uma Unit por vez, com o ponto de controle verificado ao fim de cada uma. É o que o estado já registra, e o trabalho é de uma pessoa só. Depois do esqueleto aprovado, o framework oferece seguir sozinho ou revisar cada ponto de controle. (recomendado)
B. U3 e U4 em paralelo
X. Other (please specify)

[Answer]: A

## Q5. Dependências externas

De que coisas de fora os Bolts dependem?

A. As dependências externas são:
   - **Performance Validation do intent 261113.** Bloqueia o B4. Depende do texto do `load-test.yml` que o responsável aplica. Se atrasar, o B4 espera, e a U4 fica com a flag desligada.
   - **Spec novo no `scripts/verify.sh`.** Bloqueia o ponto de controle do B1. É o responsável quem acrescenta o spec, com o texto pronto que eu entrego. Se atrasar, o ponto de controle do B1 espera.
   - **Cota do GitHub Actions.** Afeta todos os Bolts. Se apertar, a frequência dos jobs é revista com dados de consumo.

   Nenhuma API de terceiros nova. (recomendado)
B. Nenhuma dependência externa
X. Other (please specify)

[Answer]: A

## Q6. O que mais preocupa

O que mais preocupa nesta construção?

A. O dinheiro do REAL. São três pontos:
   - o rateio e a conferência exata com o ledger (U2);
   - a ordem pela rede sem quebrar o ledger que não se edita (tabela à parte);
   - o bot REAL nunca vender a parte de outro (U4).

   Por isso a U2 vem logo depois do esqueleto, com os testes antes do código, e a U4 entra por último, desligada. A U1 não é implantada sem a U2. (recomendado)
B. A tela
X. Other (please specify)

[Answer]: A

## Q7. Comando de verificação dos pontos de controle

Que comando verifica cada ponto de controle?

A. `bash scripts/verify-checkpoint.sh`, o mesmo do intent 261113. Ele roda primeiro o `verify.sh` local, com todos os specs de ponta a ponta. Se a máquina não aguentar, a prova é a execução verde do `ci` do mesmo commit. A escolha passa pela sua aprovação numa pergunta própria. (recomendado)
B. `bash scripts/verify.sh`
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Ordem:** primeiro o esqueleto (U1), depois o risco (U2, o núcleo financeiro), depois a tela (U3) e por último o ciclo (U4), que só entra depois da Performance Validation do REAL [Q1].
- **WSJF:** só para ordenar U3 e U4, com pesos iguais. A ordem U1 → U2 é fixada pelo esqueleto e pelo grafo [Q2].
- **Bolts:** um Bolt por Unit, B1 a B4. A U2 tem um ponto de controle interno e é dividida se ele atrasar [Q3].
- **Execução:** em série, uma Unit por vez, com um ponto de controle verificado ao fim de cada uma [Q4].
- **Dependências externas** [Q5]:
  - a Performance Validation do 261113 bloqueia o B4;
  - o spec novo no `verify.sh`, que o responsável acrescenta, bloqueia o ponto de controle do B1;
  - a cota do GitHub Actions afeta todos os Bolts.
- **Maior preocupação:** o dinheiro do REAL. Por isso a U2 vem logo depois do esqueleto, com os testes antes do código, a U4 entra por último e desligada, e a U1 não é implantada sem a U2 [Q6].
- **Verificação:** `bash scripts/verify-checkpoint.sh`, com aprovação numa pergunta própria [Q7].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
