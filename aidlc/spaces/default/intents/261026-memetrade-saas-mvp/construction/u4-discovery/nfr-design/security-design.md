# Desenho de segurança — U4 Descoberta e avaliação

Como a U4 cumpre os requisitos de [security-requirements.md](../nfr-requirements/security-requirements.md), com as decisões de [nfr-design-questions.md](nfr-design-questions.md) e os achados R-02 e R-06 de [review-01.md](../nfr-requirements/reviews/review-01.md). A U4 reaproveita os filtros da U1 (autenticação, caminho normalizado, papel administrativo) e o publicador de auditoria da U2.

## Cadeia de uma requisição às rotas da U4

| Ordem | Filtro ou componente | O que faz na U4 | Requisito |
|---|---|---|---|
| 1 | Correlação (U1) | Gera ou aceita `X-Correlation-Id` | NFR9.7 |
| 2 | Autenticação (U1, caminho normalizado) | Sem token: `401 UNAUTHENTICATED`, sem contar no limite. Token de administrador numa rota do convidado: `403 FORBIDDEN_ROLE` auditado (regra comum do contrato: o token do administrador só vale em `/api/v1/admin/...`) | NFR6.1, achado R-06 |
| 3 | Limite de requisições (novo, U4) | Conta por pessoa e por rota (Q2): até 60 em 60 s na lista e 60 no detalhe; acima, `429` com `Retry-After` e código `RATE_LIMITED` | NFR6.3 |
| 4 | Validação dos parâmetros (controlador) | BR2.7 e limites de tamanho; `400 VALIDATION_FAILED` com a lista de campos | NFR6.2 |
| 5 | Consulta parametrizada | Nenhum valor do pedido entra no texto do SQL; ordenação escolhida de uma lista fechada | NFR6.2 |

Papéis nas rotas da U4: sem token, `401`; convidado (`BETA_USER`), `200`; administrador, `403 FORBIDDEN_ROLE`. Um teste por papel em cada rota.

## Limite de requisições (NFR6.3, NFR2.8, achado R-02)

- **Chave:** identificador da pessoa do `Principal` mais o nome da rota (`scanner` ou `assessment`); nunca carteira nem IP.
- **Algoritmo:** janela deslizante de 60 s guardada como fila de instantes por chave (no máximo 60 instantes); o instante vem do relógio injetável.
- **Resposta acima do limite:** `429` com `Retry-After` igual aos segundos até o instante mais antigo sair da janela (arredondado para cima, mínimo 1); `ProblemDetail` com `code: RATE_LIMITED` e `correlationId`, sem contagens.
- **Memória:** chaves sem requisição há mais de 2 minutos são removidas por uma limpeza a cada minuto; teto de 10 000 chaves (acima disso, a chave mais antiga sai primeiro). Teste da remoção com relógio injetável.
- **Contrato:** `RATE_LIMITED` (429) acrescentado à tabela de códigos comuns pelo Contract Design desta Unit (functional-spec.md, acréscimos).
- **Limitação declarada:** por instância de processo; revisto com a hospedagem.

## Versões de pesos (NFR6.4)

- `RiskWeightAdministration` recebe o `Principal` e chama `AdminAccess.requireAdministrator` (componente único da U1 que conhece `Role.ADMIN`, regra ArchUnit existente).
- Recusa: `403 FORBIDDEN_ROLE` com um `REQUEST_FORBIDDEN` pela porta da U1.
- Ativação: uma transação que trava a linha da versão ativa (`SELECT ... FOR UPDATE`), troca os estados e publica `RISK_WEIGHTS_ACTIVATED` pelo publicador da U2 (C9), com ator, versão anterior e nova em `details`; reativar a mesma versão não grava nada.
- Nenhum código de produção da U4 cria um `Principal` de administrador; teste ArchUnit.

## Dados das fontes como entrada não confiável (NFR6.5, NFR6.6)

- Validação na porta `MarketSourceAdapter`, antes de qualquer gravação: `chain` até 64 caracteres em minúsculas e dígitos; `mint` Base58 de 32 a 44 caracteres; `symbol` até 32 e `name` até 120 caracteres, sem caracteres de controle (categoria Unicode Cc e Cf); decimais não negativos com no máximo 30 dígitos e escala até 18; no máximo 5 000 tokens por coleta (o restante é descartado e contado como `TOO_MANY`).
- O app web exibe `symbol` e `name` como texto (React escapa por padrão); nenhum `dangerouslySetInnerHTML` nas telas da U4 (regra de lint).
- Fontes reais (futuras): endereço base só da configuração, cliente HTTP sem seguir redirecionamentos, lista de hosts permitidos; nenhum adaptador simulado abre conexão de rede (teste que falha se algum socket for aberto durante a coleta simulada).

## Dependência nova (NFR6.7)

Resilience4j (`resilience4j-retry` e `resilience4j-timelimiter`) com versão fixa no `pom.xml`, sem o módulo de integração com Spring (configuração por código, para manter o relógio e a espera injetáveis nos testes). Coberta pelo OWASP Dependency-Check e pelo CodeQL já existentes.

## Textos proibidos (NFR6.8, BR5.1)

Arquivo versionado `apps/web/src/content/prohibited-terms.json` com versão e revisor; teste Jest que percorre o catálogo de textos do app do convidado normalizando maiúsculas e acentos (NFD sem marcas) e falha nomeando o texto e o termo. Roda na CI no job do frontend.

## Sem caminho de operação (NFR6.9)

Regras ArchUnit: `riskassessment`, `marketdata` e `scanner` não dependem de pacotes de bots, ordens, ledger ou carteira; nenhuma classe da U4 importa bibliotecas de assinatura.

## Integridade dos cálculos (NFR4.1, NFR4.2, NFR4.3, NFR8.1)

- `BigDecimal` com `RoundingMode.HALF_UP` nos pontos que as regras definem; `numeric` no banco; JSON com decimais em texto. Uma regra ArchUnit proíbe `double` e `float` nos pacotes da U4.
- `MarketValue` selado (`Known` ou `Unknown`) do adaptador à resposta; nenhum `null` para ausência.
- Teste de propriedade com semente fixa: 1 000 combinações de entradas dão indicador entre 0 e 100, igual em duas execuções, e nunca `LOW` com fator `Unknown`.
- A avaliação guarda versão, entradas e horários (NFR8.1); um teste recalcula a partir do guardado e compara.

## Dados e privacidade (NFR5.1, NFR7.1)

Tabelas da U4 sem `tenantId`; as duas rotas entram no registro de rotas da U1 como globais. Logs e métricas da U4 sem carteira, token ou identificador de pessoa; a varredura de logs da U2 (`LogScanIT`) passa a cobrir a suíte da U4.

## Modelo de ameaças (resumo)

O modelo STRIDE completo está em [security-requirements.md](../nfr-requirements/security-requirements.md); este desenho dá a mitigação concreta de cada linha:

| Ameaça | Mitigação desenhada |
|---|---|
| Falsificação | Filtro de autenticação da U1 com caminho normalizado; papel por `AdminAccess` |
| Adulteração | Validação na porta do adaptador; versões ativadas imutáveis |
| Repúdio | `RISK_WEIGHTS_ACTIVATED` na trilha da U2 |
| Divulgação | `ProblemDetail` sem detalhes; logs sem dado pessoal |
| Negação de serviço | Limite por pessoa e rota; teto de 5 000 tokens; tempo limite das fontes |
| Elevação de privilégio | ArchUnit sem dependência de operações; `unknown` nunca seguro |
