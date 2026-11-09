# Plano das histórias — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md` (FR1 a FR8, NFR1 a NFR9).

Plano proposto:
- formato: "Como [persona], quero [objetivo], para [benefício]", seguindo INVEST;
- critérios Given/When/Then;
- IDs `US{grupo}.{n}` e `AC{grupo}.{n}.{m}`;
- prioridade MoSCoW vinda dos requisitos.

## Q1. Quais as personas?

A) Três:
- **Visitante**, sem login: lê os termos e a política;
- **Convidado**, titular dos dados: aceita, acompanha a versão, faz e acompanha pedidos;
- **Administrador**, que é o controlador e o encarregado no beta: atende pedidos, anonimiza e publica versões.

(recomendado)

B) Só convidado e administrador
X) Other (please specify)

[Answer]: A

## Q2. Como dividir as histórias?

A) Por fluxo, em seis grupos:
- US1: documentos e publicação (FR1);
- US2: páginas e acessos (FR2);
- US3: retenção e expurgos (FR3);
- US4: novo aceite (FR4);
- US5: pedidos de titular (FR5);
- US6: anonimização (FR6).

Mais as histórias Should e Could (FR7, FR8) no grupo US5 e no US2 (recomendado)

B) Por persona
X) Other (please specify)

[Answer]: A

## Q3. Que granularidade?

A) Uma história por comportamento que a pessoa percebe, de 1 a 3 dias de trabalho cada, com 2 a 5 critérios. Os expurgos técnicos (FR3) viram histórias do administrador ("como administrador, quero que os dados vencidos saiam sozinhos") (recomendado)
B) Histórias maiores, uma por requisito
X) Other (please specify)

[Answer]: A

## Q4. Como confirmar a identidade de quem pede pelo e-mail do encarregado?

Ponto Menor que ficou da revisão dos requisitos (FR5.2c).

A) Antes de liberar o resumo de Acesso ou de anonimizar, o administrador pede que a pessoa assine, com a carteira da conta, uma mensagem de verificação gerada pelo sistema para aquele pedido (válida por 24 horas e só para aquele pedido; não serve para login nem para operar). Sem assinatura válida, o pedido é recusado com o motivo "identidade não confirmada". Se a carteira foi perdida, o pedido é recusado com orientação, sem dado pessoal na resposta (recomendado)
B) Confiar no e-mail sem verificação
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Personas: Visitante (sem login), Convidado (titular) e Administrador (controlador e encarregado no beta).
- Divisão por fluxo: US1 documentos e publicação, US2 páginas e acessos (com FR8), US3 retenção e expurgos, US4 novo aceite, US5 pedidos de titular (com FR7), US6 anonimização.
- Granularidade: uma história por comportamento percebido, 1 a 3 dias, 2 a 5 critérios Given/When/Then; expurgos como histórias do administrador.
- Identidade em pedidos por e-mail: assinatura com a carteira da conta de uma mensagem de verificação do pedido (24 h, só para aquele pedido, não serve para login nem para operar) antes de liberar Acesso ou anonimizar; sem assinatura, recusa com motivo.

- Looks correct
- Request changes

[Answer]: Looks correct
