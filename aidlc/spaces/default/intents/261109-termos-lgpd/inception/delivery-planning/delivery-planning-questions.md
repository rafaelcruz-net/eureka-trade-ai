# Perguntas do Planejamento de Entrega — Termos de uso e LGPD

Conversa: português (Brasil). Base:
- requisitos, histórias, componentes, Units (`u1-legal-documents` → `u2-retention`, `u3-reacceptance` → `u4-data-requests` → `u5-anonymization`) e contratos em `inception/`;
- formação do time em `ideation/team-formation/`;
- as práticas do time (`team.md`): commits direto em `main`, uma Unit por vez, fatia fina primeiro.

Um **Bolt** é uma passada de construção sobre uma parte do trabalho, do design ao código, que termina em algo que roda e é verificado.

## Q1. O que construir primeiro?

A) Começar pela fatia fina, uma versão mínima que atravessa o sistema inteiro: U1, com `/privacidade` servida pela API e mostrada sem login. Depois seguir a ordem das dependências, pondo o risco cedo dentro dela:
- U2 (retenção e auditoria sem a carteira, que destrava a anonimização sem resíduo);
- U3 (novo aceite);
- U4 (pedidos);
- U5 (anonimização, a de maior risco, que depende de U2 e U4).

(recomendado)

B) Valor primeiro: U4 (pedidos) logo depois de U1
X) Other (please specify)

[Answer]: A

## Q2. Usar um modelo de pontuação para ordenar?

A) Não precisa: com 5 Units e um grafo que já fixa quase toda a ordem (só U2 e U3 podem trocar de lugar), a ordem vem das dependências. Entre U2 e U3, U2 vem antes por reduzir o risco da anonimização (recomendado)
B) Pontuar com WSJF (valor e urgência divididos pelo tamanho)
X) Other (please specify)

[Answer]: A

## Q3. Qual o tamanho de um Bolt?

A) Um Bolt por Unit: 5 Bolts (recomendado)
B) Juntar U2 e U3 num Bolt só
X) Other (please specify)

[Answer]: A

## Q4. Bolts ao mesmo tempo ou um de cada vez?

A) Um de cada vez, como o time faz, com ponto de controle no fim de cada um. A U1, como fatia fina, tem a aprovação separada do esqueleto (recomendado)
B) Em paralelo onde o grafo permite (U2 e U3)
X) Other (please specify)

[Answer]: A

## Q5. O que fora do time pode atrasar?

A) Quatro dependências externas, nenhuma bloqueando a construção:
- texto final revisado pela assessoria;
- e-mail do encarregado;
- local de armazenamento, que depende da hospedagem;
- as mudanças no `ci.yml` e o spec novo no `verify.sh`, que só você altera.

As três primeiras só bloqueiam a publicação da versão do beta. A última precisa acontecer antes do primeiro commit e do ponto de controle da U1 (recomendado)

B) Há outra dependência (diga qual)
X) Other (please specify)

[Answer]: A

## Q6. O que mais preocupa?

A) A anonimização deixar a carteira em algum lugar (tabela, log, desafio) ou quebrar o ledger. Mitigação: U2 (minimização) antes de U5, testes escritos antes do código, teste obrigatório "sem resíduo" e testes de falha, repetição e concorrência (recomendado)
B) A política dizer algo que o sistema não faz
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Ordem: fatia fina primeiro (U1, `/privacidade` de ponta a ponta), depois a ordem das dependências com o risco cedo: U2, U3, U4, U5.
- Sem modelo de pontuação: o grafo fixa a ordem; U2 antes de U3 por reduzir o risco da anonimização.
- Um Bolt por Unit: 5 Bolts.
- Um Bolt de cada vez, com ponto de controle no fim de cada um; aprovação do esqueleto na U1.
- Dependências externas: texto final da assessoria, e-mail do encarregado e local de armazenamento bloqueiam só a publicação; `ci.yml` e o spec novo no `verify.sh` (responsável do projeto) antes do primeiro commit e do ponto de controle da U1.
- Maior preocupação: resíduo da carteira ou dano ao ledger na anonimização; mitigação com U2 antes de U5, testes antes do código, teste "sem resíduo" e testes de falha, repetição e concorrência.

- Looks correct
- Request changes

[Answer]: Looks correct
