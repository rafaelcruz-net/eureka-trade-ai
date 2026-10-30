# Perguntas — Functional Design da U1 (esqueleto de ponta a ponta)

Objetivo: fechar as regras de negócio e os fluxos da U1 antes do código. A U1 é a fatia fina de ponta a ponta: fundação do repositório, fonte de mercado simulada, lista mínima do scanner com fonte, horário e `unknown`, login por assinatura de carteira e gravação de auditoria pela tabela de saída. A definição está em [unit-of-work.md](../../../inception/units-generation/unit-of-work.md) e os contratos em [contract-summary.md](../../../inception/contract-design/contract-summary.md) (C1, C2, C9, C10).

Já decidido e que não volta a ser perguntado: o token de acesso fica na memória e a renovação usa cookie `HttpOnly` (Q11 e Q13 do Contract Design); `unknown` é um objeto com `status` (Q5); os decimais trafegam como texto (Q6); a interface de auditoria final e a tabela de saída já existem na U1 (Q10, Q18); o papel `ADMIN` só entra pela entrada administrativa (Q20); toda requisição que muda estado exige `Idempotency-Key` (Q7).

## Q1. [Arquitetura] Como a U1 trata a entrada do administrador?

O critério AC1.2.6 (a carteira de administrador entra sem convite) foi escrito para a U1, mas o Contract Design levou a entrada do administrador para a API administrativa, que nasce com o app do administrador na U8.

A. A U1 entrega só a entrada do convidado; o AC1.2.6 passa para a U8, e a U1 já recusa a carteira de administrador na entrada do convidado com `403 FORBIDDEN_ROLE`.
B. A U1 entrega também as rotas `/api/v1/admin/auth/...`, ainda sem app do administrador.
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] O que acontece com o evento de auditoria quando a ação é recusada?

Revisão do Contract Design (R-13): o evento de auditoria é gravado na mesma transação da ação. Uma recusa de login (nonce reutilizado, assinatura expirada, mensagem alterada) termina num erro, e, se esse erro desfizer a transação, o evento some, contrariando "exatamente um evento por ação sensível" (FR2.3).

A. Recusas são resultados de domínio, não exceções: a transação confirma, grava o evento de recusa (e marca o nonce como gasto quando for o caso) e só então a API responde com o erro.
B. O evento de recusa é gravado numa transação própria e independente, que sobrevive ao desfazer da transação principal.
X. Other (please specify)

[Answer]: A

## Q3. [Segurança] Como a repetição do `verify` se comporta?

Revisão do Contract Design (R-18): guardar a resposta do `verify` para repetição com a mesma `Idempotency-Key` reteria o token de acesso em repouso por 24 horas. O nonce já é de uso único.

A. O `verify` não guarda a resposta: o próprio nonce garante uma única sessão; se a resposta se perder na rede, o app pede um novo desafio e a pessoa assina de novo.
B. O `verify` guarda só o resultado sem o token, e a repetição emite uma sessão nova.
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Quais campos a lista mínima do scanner mostra na U1?

A U1 prova a fatia; filtros, risco e divergência ficam para a U4. Cada valor mostra fonte, horário e frescor ao passar o mouse ou focar (AC2.1.1).

A. Símbolo, mint abreviado, preço, liquidez, volume em 24 h, holders e idade do token, mais a cobertura ("5 de 6 campos"); ordem padrão pela idade, mais novos primeiro, com desempate pelo mint.
B. Só símbolo, mint abreviado, preço, liquidez e holders, mais a cobertura; mesma ordem padrão.
X. Other (please specify)

[Answer]: A

## Q5. [Plataforma] Como a fonte simulada gera os dados?

Os testes exigem determinismo (relógio injetável, sementes fixas, nenhuma rede) e o smoke precisa conferir um campo `unknown` e um zero informado (AC2.1.2).

A. Um conjunto fixo de tokens num arquivo de dados versionado (com pelo menos um token sem holders informados e um com liquidez informada como 0); a cada coleta, os valores variam de forma determinística a partir do relógio injetável.
B. Um gerador aleatório com semente fixa, que cria tokens e valores a cada coleta.
X. Other (please specify)

[Answer]: B

## Q6. [Segurança] Como o smoke cria a conta de teste?

O smoke entra com uma conta `BETA_USER` semeada só no perfil de teste (OQ-8). A carteira de teste não pode ter chave guardada no repositório (varredura de segredos e regra de nunca usar carteira real).

A. O smoke gera um par de chaves na hora, insere a conta, o tenant e a carteira pública direto no banco de teste antes de chamar o login, e descarta a chave no fim.
B. Uma rota de criação de conta existe só no perfil de teste, e o smoke a chama com a carteira gerada na hora.
C. Uma carteira de teste fixa, com a chave privada num arquivo de teste versionado.
X. Other (please specify)

[Answer]: A

## Q7. [Segurança] Quanto duram o token de acesso, a renovação e a sessão no esqueleto?

Valores provisórios para a U1 (OQ-4), revistos no NFR Requirements.

A. Token de acesso de 15 minutos, renovação válida por 8 horas e sessão absoluta de 12 horas.
B. Token de acesso de 5 minutos, renovação válida por 24 horas e sessão absoluta de 7 dias.
X. Other (please specify)

[Answer]: A

## Q8. [Segurança] A U1 já limita tentativas de login?

As rotas de desafio e de verificação não têm sessão e podem ser chamadas em volume (R-17 do Contract Design pede `429`).

A. Sim: limite simples por endereço de carteira e por IP no desafio e na verificação (proposta: 10 por minuto), com `429` e `Retry-After`; valores revistos no NFR Design.
B. Não: deixar a limitação para o NFR Design e a U3.
X. Other (please specify)

[Answer]: A

## Q9. [Qualidade] O gerador simulado garante os casos que o smoke confere?

Seguimento de Q5 (gerador com semente). O smoke e o critério AC2.1.2 precisam encontrar sempre um campo não informado (`unknown`) e um campo informado como 0. Um gerador aleatório pode não produzir esses casos.

A. O gerador usa semente fixa e sempre inclui, além dos tokens aleatórios, pelo menos um token com holders não informados e um com liquidez informada como 0.
B. O smoke procura esses casos no que vier e falha se não encontrar.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- A U1 entrega só a entrada do convidado; a carteira de administrador recebe `403 FORBIDDEN_ROLE` nessa entrada, e o AC1.2.6 passa para a U8 (Q1).
- Recusa de login é resultado de domínio: a transação confirma, grava o evento de recusa na tabela de saída (e marca o nonce como gasto quando for o caso) e só então a API responde com o erro (Q2).
- O `verify` não guarda resposta para repetição: o nonce de uso único garante uma única sessão; resposta perdida exige novo desafio e nova assinatura (Q3).
- Lista mínima do scanner com símbolo, mint abreviado, preço, liquidez, volume em 24 h, holders e idade, mais a cobertura; ordem padrão pela idade (mais novos primeiro) com desempate pelo mint (Q4).
- Fonte simulada por gerador aleatório com semente fixa, que sempre inclui pelo menos um token com holders não informados e um com liquidez informada como 0 (Q5, Q9).
- O smoke gera um par de chaves na hora, insere tenant, conta e carteira pública direto no banco de teste antes do login e descarta a chave no fim (Q6).
- Token de acesso de 15 minutos, renovação válida por 8 horas e sessão absoluta de 12 horas, provisórios até o NFR Requirements (Q7).
- Limite de 10 tentativas por minuto por carteira e por IP no desafio e na verificação, com `429` e `Retry-After`, revisto no NFR Design (Q8).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
