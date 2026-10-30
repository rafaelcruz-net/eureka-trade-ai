# Perguntas do desenho funcional — U2 Trilha de auditoria

Contexto: a U1 já entregou a interface publicadora final (`AuditEventPublisher`), a tabela de saída gravada na transação da ação, o relay que grava cada evento uma única vez, a proteção append-only de `audit_event` por gatilho, o tipo `WalletAddress` mascarado e o filtro Base58 nos logs. A U2 completa a trilha (US7.3, AC7.3.1 a AC7.3.3, FR2.3, NFR7). As perguntas abaixo cobrem só o que ainda não foi decidido em etapas anteriores.

## Q1. [Produto] Como a trilha é consultada no beta?

A U2 tem "consulta restrita da trilha" como responsabilidade, mas o contrato administrativo (C8) não tem rota de auditoria e a U2 não tem tela própria no beta.

- A. Só uma interface pública do módulo (`AuditQuery`), usada por testes e, no futuro, pela administração; nenhuma rota REST no beta
- B. Rota administrativa só de leitura `GET /api/v1/admin/audit-events` (acrescenta uma operação ao contrato C8), sem tela; consulta por ferramenta HTTP com token de administrador
- C. Nenhuma consulta pela aplicação; o responsável consulta direto no banco com um roteiro documentado
- X. Other (please specify)

[Answer]: B

## Q2. [Produto] Por quais critérios a consulta filtra os eventos?

Vale para a interface ou para a rota escolhida na Q1 (select all that apply).

- A. Período (de/até, em UTC)
- B. Tenant
- C. Ação (por exemplo `LOGIN_REJECTED`)
- D. Ator
- E. Identificador de correlação
- X. Other (please specify)

[Answer]: A, C

## Q3. [Segurança] Na consulta, o endereço da carteira aparece completo ou mascarado?

O NFR7 diz que o endereço completo só aparece na trilha, com acesso restrito; a U1 guarda o completo em `details` e o mascarado em `actor`.

- A. Completo em `details`, só para o administrador (mascarado em qualquer outra saída)
- B. Sempre mascarado na consulta; o completo fica só no banco
- X. Other (please specify)

[Answer]: A

## Q4. [Compliance] Qual a retenção dos eventos da trilha no beta?

O NFR7 pede "retenção mínima após inventário" e uma política aprovada antes dos convites. A trilha é append-only: apagar eventos antigos exige um caminho de expurgo próprio, fora da aplicação.

- A. Nenhum expurgo no beta; a política de retenção fica registrada como pendência obrigatória antes dos convites (bloqueia o G1)
- B. Definir agora um prazo (por exemplo 12 meses) e um expurgo agendado com papel de banco separado, auditado
- X. Other (please specify)

[Answer]: B

## Q5. [Operações] O que acontece com as linhas já entregues da tabela de saída?

Cada evento entregue fica na tabela de saída com `delivered_at` preenchido; a tabela cresce sem limite.

- A. Uma limpeza diária apaga linhas entregues há mais de 7 dias (a cópia permanente está na trilha)
- B. Manter todas as linhas no beta e decidir a limpeza no NFR Design
- X. Other (please specify)

[Answer]: A

## Q6. [Desenvolvimento] Como as ações auditadas são registradas para todas as Units?

O contrato C9 define `action` como texto livre. Cada Unit publica os próprios eventos (DP-2).

- A. Catálogo central de ações (lista declarada no módulo de auditoria); o publicador recusa ação fora do catálogo e um teste confere que cada ação do catálogo tem um teste de "exatamente um evento"
- B. Texto livre como está no C9; cada Unit documenta e testa as suas ações
- X. Other (please specify)

[Answer]: B

## Q7. [Segurança] O que a varredura automática procura nos logs, nas métricas e nos eventos?

A U1 varre os logs dos testes procurando o endereço completo. AC7.3.2 e AC7.3.3 ampliam a varredura (select all that apply).

- A. Endereço de carteira completo (Base58 de 32 a 44 caracteres) em logs e métricas
- B. Tokens de acesso e de renovação (formato JWT e valores de cookie de renovação)
- C. Cabeçalho `Authorization` e o prefixo `Bearer`
- D. Nonces e assinaturas de login emitidos durante os testes (valores conhecidos pelo teste)
- E. Os mesmos padrões também no `payload` da tabela de saída e em `details` da trilha
- X. Other (please specify)

[Answer]: A, B, C

## Q8. [Compliance] Qual o prazo de retenção dos eventos da trilha?

Na Q4 você escolheu definir o prazo agora, com expurgo agendado por um papel de banco separado e auditado. Falta o prazo.

- A. 6 meses
- B. 12 meses
- C. 24 meses
- D. 5 anos
- X. Other (please specify)

[Answer]: B

## Q9. [Segurança] A varredura também cobre nonces, assinaturas e os eventos?

Na Q7 ficaram de fora os nonces e assinaturas (D) e a varredura dos eventos (E). O critério AC7.3.3 diz: "Dados os eventos e os logs, quando inspecionados pela varredura, então não contêm segredos, tokens de sessão, cabeçalhos `Authorization`, nonces nem assinaturas." Sem D e E, esse critério fica sem verificação automática.

- A. Incluir D e E: a varredura procura também os nonces e assinaturas emitidos nos testes, em logs, métricas e eventos (cumpre AC7.3.3)
- B. Manter só A, B e C e marcar a parte de nonces, assinaturas e eventos de AC7.3.3 como adiada, com o motivo
- X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
