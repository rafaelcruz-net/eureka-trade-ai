# Perguntas — Delivery Planning

Objetivo: escolher a ordem em que as 9 Units de [unit-of-work.md](../units-generation/unit-of-work.md) são construídas. Cada etapa de construção é um **Bolt**: uma passada de construção sobre uma parte do trabalho que termina em algo que roda. O grafo de [unit-of-work-dependency.md](../units-generation/unit-of-work-dependency.md) diz o que pode depender de quê; aqui decidimos o que vem primeiro e por quê.

Já decidido e que não volta a ser perguntado: o primeiro Bolt é a fatia fina de ponta a ponta (U1, `team.md`); desenvolvimento baseado em tronco com squash-merge em `main`; você trabalha sozinho com apoio de IA, sem formação de equipe (Team Formation foi pulada), então todos os Bolts são executados pelo agente desenvolvedor com a sua aprovação; TDD no núcleo financeiro.

## Q1. [Entrega] Depois do esqueleto, o que vem primeiro?

A U1 abre o caminho. Depois dela, o grafo permite começar pela descoberta (U4), pela auditoria (U2) ou pela identidade (U3), e o núcleo financeiro (U7, U6, U5) depende da U4.

A. Risco primeiro: ir o quanto antes ao núcleo financeiro (U4 → U7 → U6 → U5), porque é onde estão as regras mais difíceis e as decisões de contrato ainda abertas.
B. Valor primeiro: completar descoberta e avaliação (U4) e o acesso por convite (U2, U3) antes do núcleo, para já poder mostrar o scanner a convidados.
C. Misto: U2 e U4 logo depois do esqueleto (fundação de auditoria e dados), depois o núcleo financeiro (U7, U6, U5) e por último identidade completa, administração e painel (U3, U8, U9).
X. Other (please specify)

[Answer]: C

## Q2. [Entrega] Usamos uma pontuação formal para ordenar os Bolts?

WSJF (Weighted Shortest Job First) é uma pontuação que soma valor, urgência e redução de risco e divide pelo tamanho do trabalho; quem tem nota maior vai primeiro.

A. Não: a ordem fica justificada por escrito, com o argumento de risco ou valor escolhido em Q1.
B. Sim, WSJF com pesos iguais para valor, urgência e redução de risco.
C. Sim, WSJF com peso dobrado para redução de risco.
X. Other (please specify)

[Answer]: B

## Q3. [Entrega] Qual o tamanho de um Bolt?

A revisão da Units Generation apontou que a U5 (bots e decisão) é muito grande: 11 histórias, agendador, máquina de estados e 3 telas.

A. Um Bolt por Unit (9 Bolts).
B. Um Bolt por Unit, menos a U5, que vira dois Bolts: primeiro o ciclo (decisão, barreira, execução, pausas e encerramento), depois as telas restantes e a edição de limites (10 Bolts).
C. Juntar Units pequenas e próximas no mesmo Bolt (por exemplo, U2 com U3, U8 com U9).
X. Other (please specify)

[Answer]: A

## Q4. [Entrega] Units independentes podem ser construídas ao mesmo tempo?

O AI-DLC pode construir Units sem dependência entre si em cópias separadas do repositório, mas cada uma ainda passa pela sua aprovação.

A. Não: uma Unit por vez, do design ao código, com um ponto de controle no fim de cada uma.
B. Sim, quando houver Units independentes prontas para começar, construir em paralelo.
X. Other (please specify)

[Answer]: A

## Q5. [Entrega] Quem cuida da comparação de fontes (SP-1) e o que acontece se ela atrasar?

A divergência entre fontes (US2.4, na U4) e os adaptadores reais dependem do resultado da SP-1 no G0. A revisão da Units Generation apontou que a SP-1 não tem responsável nem prazo.

A. Você é o responsável, com conclusão antes do fim da U4; a U4 é aceita com fontes simuladas e tolerância de divergência configurável, e os adaptadores reais entram depois do G0 sem bloquear os demais Bolts.
B. A SP-1 bloqueia a U4: a U4 só termina com as fontes reais aprovadas.
C. A SP-1 fica para depois do beta interno; o beta inteiro roda com fontes simuladas.
X. Other (please specify)

[Answer]: A

## Q6. [Entrega] O que mais preocupa você nesta construção?

O que mais preocupa vai para o começo da fila, para ser enfrentado cedo.

A. O núcleo financeiro dar resultados errados (barreira, ledger, P&L).
B. A integração do ciclo dos bots no processo worker e a auditoria (os pontos críticos da revisão de contratos).
C. Os dados de mercado e as fontes reais.
D. O login por carteira e a segurança da sessão.
X. Other (please specify)

[Answer]: A

## Q7. [Arquitetura] Quando resolver os pontos críticos aceitos no Contract Design?

Você aprovou o Contract Design com riscos aceitos: um ciclo entre U5 e U7 pela regra de estado do bot, um segundo entre U6 e U7 pelas recusas de ordem, e a auditoria do worker sem caminho de entrega.

A. Como condição de pronto do Functional Design da primeira Unit afetada: a U2 define a entrega da auditoria do worker, e a U7 define como lê o estado do bot sem depender da U5, antes de qualquer código dessas Units.
B. Voltar agora ao Contract Design e corrigir antes de seguir.
C. Resolver quando a U5 for construída.
X. Other (please specify)

[Answer]: B

## Q8. [Qualidade] Quando o caminho PAPER completo é testado de ponta a ponta?

Até a U5 existir, a U6 e a U7 só são testadas com dados de teste. A revisão sugeriu um teste de contrato entre U6 e U7 antes da U5.

A. Incluir na definição de pronto da U6 um teste de contrato entre barreira e ledger, e na da U5 um teste do ciclo completo (decisão, barreira, execução, ledger).
B. Só o teste do ciclo completo, quando a U5 for construída.
X. Other (please specify)

[Answer]: A

## Q9. [Plataforma] O Docker já está instalado e funcionando no seu Windows?

O script de verificação do esqueleto e os testes de integração com PostgreSQL precisam de Docker. Isso define se o comando de verificação pode rodar todos os níveis de teste desde o início.

A. Sim, o Docker Desktop está instalado e funcionando.
B. Não ainda; instalo antes de começar a U1.
C. Não pretendo usar Docker Desktop; uso outra forma de rodar contêineres (por exemplo, WSL com Docker Engine).
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Ordem mista: esqueleto (U1); depois auditoria (U2) e descoberta (U4); depois o núcleo financeiro (U7, U6, U5); por último identidade completa, administração e painel (U3, U8, U9) (Q1).
- Ordem justificada por WSJF com pesos iguais para valor, urgência e redução de risco, divididos pelo tamanho (Q2).
- Um Bolt por Unit: 9 Bolts (Q3).
- Uma Unit por vez, do design ao código, com ponto de controle no fim de cada uma; nada em paralelo (Q4).
- SP-1 sob sua responsabilidade, concluída antes do fim da U4; a U4 é aceita com fontes simuladas e tolerância configurável, e os adaptadores reais entram depois do G0 sem bloquear os demais Bolts (Q5).
- Maior preocupação: o núcleo financeiro dar resultado errado (barreira, ledger, P&L) (Q6).
- Os pontos críticos do contrato foram corrigidos voltando ao Contract Design antes de seguir (Q7, já feito).
- Teste de contrato entre barreira e ledger no pronto da U6 e teste do ciclo completo (decisão, barreira, execução, ledger) no pronto da U5 (Q8).
- Docker Desktop instalado e funcionando: o comando de verificação pode rodar testes de unidade, integração com PostgreSQL em contêiner e o smoke de ponta a ponta desde a U1 (Q9).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
