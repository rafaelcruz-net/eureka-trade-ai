# Decisões de tecnologia — U3 Convites, contas e sessões

A U3 usa a stack prescrita do projeto (Constraints de [requirements.md](../../../inception/requirements-analysis/requirements.md)) e as escolhas já feitas na U1, na U2 e na U4. Nenhuma biblioteca nova entra: geração e resumo do código usam a biblioteca padrão do Java.

| Área | Escolha | Situação | Justificativa |
|---|---|---|---|
| Linguagem e framework do backend | Java 21, Spring Boot 3.5 (módulo `identity` por pacote) | Mantida | Stack prescrita; monólito modular (U1) |
| Código do convite | `SecureRandom` com 32 bytes e `Base64.getUrlEncoder().withoutPadding()` | Nova (sem biblioteca) | 256 bits, 43 caracteres (NFR6.1). Alternativa rejeitada: UUID v4 (122 bits, menos entropia) |
| Resumo do código | SHA-256 por `MessageDigest`, sem chave secreta (Q2) | Nova (sem biblioteca) | Com 256 bits aleatórios, adivinhar a partir do resumo não é viável; evita gerir mais um segredo. Alternativa rejeitada: HMAC-SHA-256 com chave do ambiente |
| Acesso a dados | `JdbcClient` com SQL parametrizado; bloqueio da linha do convite por `SELECT … FOR UPDATE` | Mantida | Padrão da U1; o bloqueio explícito ordena verificação e revogação (NFR4.2, NFR4.4) |
| Banco | PostgreSQL 17, com índices únicos em `invite.code_digest`, `invite.used_by_user_id` e `wallet.address` | Mantida | As unicidades são a garantia final da concorrência (BR3.4, BR3.7) |
| Migrações | Liquibase, pelo `db-migrate` com o dono do esquema | Mantida | Padrão da U2; a U3 acrescenta `invite`, `terms_acceptance` e a semeadura do tenant `Beta` (NFR10.4) |
| Idempotência | Mecanismo comum do módulo de identidade (U1), usado por interface | Mantida | Regra do `project.md`; a emissão grava o registro sem o código, na mesma transação (NFR6.3) |
| Limite de consultas do convite | Contador em memória no processo da API, como o da entrada | Mantida | Uma instância no beta (NFR2.3) |
| Termos e aviso de risco | Arquivo versionado em `apps/api/src/main/resources/terms/<versão>/`, lido na subida; a tela mostra o texto como texto simples em parágrafos, nunca como HTML | Nova | Q6 do design funcional; texto simples evita injeção de HTML |
| Relógio | `Clock` injetável | Mantida | Validade exata no limite (NFR10.6) |
| Métricas e saúde | Micrometer com Prometheus, Spring Boot Actuator | Mantida | Padrão da U1 (observability-requirements.md) |
| Testes do backend | JUnit 5, AssertJ, Testcontainers (PostgreSQL), ArchUnit, JaCoCo (80% de linhas) | Mantida | `team.md` (Testing Posture); testes de concorrência com barreira de início (NFR4.2 a NFR4.4) |
| Frontend | Next.js com TypeScript `strict`, tipos gerados do OpenAPI do convidado; marcações de aceite em `sessionStorage` | Mantida | Padrão da U1; BR6.1 |
| Testes do frontend | Jest e Testing Library; Playwright para o fluxo de entrada com convite e para a tela Conta | Mantida | Padrão da U1 |
| Carga local | `scripts/load-test.sh` ampliado para a verificação com convite, a consulta do convite e a tela Conta | Mantida e ampliada | Medição do NFR3.1 e do NFR3.2 sem ferramenta nova |

## Qualidade, acessibilidade, portabilidade e idioma

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR11.1 | Acessibilidade básica nas telas da U3 | Entrada com convite e tela Conta operáveis só por teclado, com foco visível, rótulos visíveis nas duas marcações de aceite, motivo escrito no botão indisponível, contraste ≥ 4,5:1 no tema escuro e estado do convite em texto, nunca só por cor | NFR11, `project.md` |
| NFR12.1 | Cobertura e testes por risco | Cobertura de linhas ≥ 80% no backend e no frontend, separadamente, com as exclusões listadas na configuração; testes obrigatórios de risco que a U3 toca (isolamento, idempotência, login sem reutilização, REAL desligado) presentes. A U3 não é núcleo financeiro: implementação seguida dos testes de cada camada | NFR12, `team.md` |
| NFR13.1 | Mesmo resultado no Windows e no Linux | Arquivo dos termos em UTF-8 com fim de linha LF (`.gitattributes`); a suíte da U3 passa na CI em Linux e localmente no Windows | NFR13, `team.md` |
| NFR14.1 | Textos em português do Brasil | Todos os textos da entrada com convite, dos termos, do aviso de risco e da tela Conta em pt-BR; datas e horas no fuso do navegador | NFR14, BR5.2 |
