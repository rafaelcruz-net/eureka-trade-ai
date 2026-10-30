# Perguntas — NFR Requirements da U1 (esqueleto de ponta a ponta)

Objetivo: fixar metas mensuráveis e escolhas de tecnologia que a U1 precisa para ser construída, a partir do design funcional ([functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md)), dos requisitos não funcionais NFR1 a NFR14 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Já decidido e que não volta a ser perguntado: a stack (Next.js/TypeScript, Java 21 com Spring Boot em monólito modular, PostgreSQL, Docker Compose no Windows, Maven e pnpm); piso de 80% de cobertura; testes de integração com PostgreSQL em contêiner; varredura de segredos, dependências e SAST na CI com bloqueio em Crítico e Alto; prazos de sessão (15 min, 8 h, 12 h) e limite de tentativas da U1; nenhuma meta de disponibilidade enquanto o beta roda só localmente (NFR10).

## Q1. [Desempenho] Qual meta de tempo de resposta a U1 deve cumprir?

O NFR3 é uma premissa provisória: telas principais com p95 até 2 s para 20 usuários simultâneos. A U1 tem o login e a lista do scanner.

A. Na API, p95 até 300 ms para desafio, verificação e primeira página da lista com 20 usuários simultâneos no ambiente local; na tela, a lista aparece em até 2 s (p95), conforme o NFR3.
B. Só a meta de tela do NFR3 (p95 até 2 s), sem meta própria na API.
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] Onde ficam os contadores do limite de tentativas?

A U1 roda um único processo de API no ambiente local, e a stack prevista inclui Redis.

A. Na memória do processo de API na U1; o Redis entra quando houver mais de uma instância da API.
B. No Redis desde a U1, já preparado para várias instâncias.
X. Other (please specify)

[Answer]: A

## Q3. [Plataforma] Quais ferramentas de base a U1 adota?

O `team.md` cita ferramentas só como exemplos. A U1 cria a fundação, então a escolha vale para o projeto inteiro.

A. Flyway para migrações; Vitest no frontend; Playwright no ponta a ponta; Spotless com google-java-format; Gitleaks para segredos; Semgrep para SAST; OSV-Scanner para dependências.
B. Liquibase para migrações; Jest no frontend; Playwright no ponta a ponta; Spotless com palantir-java-format; Gitleaks para segredos; CodeQL para SAST; OWASP Dependency-Check para dependências.
X. Other (please specify)

[Answer]: B

## Q4. [Segurança] Como o backend confere a assinatura da carteira?

Carteiras Solana assinam com Ed25519. O Java 21 já traz Ed25519 de forma nativa.

A. Ed25519 nativo do Java 21, mais uma decodificação Base58 pequena e testada; nenhuma biblioteca de criptografia ou SDK Solana a mais.
B. Biblioteca BouncyCastle para Ed25519.
C. Um SDK Solana para Java.
X. Other (please specify)

[Answer]: C

## Q5. [Desenvolvimento] Como o app conecta a carteira no navegador?

O beta usa Phantom e Solflare, e o smoke injeta uma carteira de teste no navegador.

A. Pelo padrão de carteiras Solana (Wallet Standard) com o adaptador oficial de carteiras; a carteira de teste se registra pelo mesmo padrão.
B. Pelas APIs injetadas de cada carteira (`window.phantom`, `window.solflare`), com código para cada uma.
X. Other (please specify)

[Answer]: A

## Q6. [Operação] Que observabilidade a U1 já entrega?

O NFR9 pede, por componente, uma métrica de saúde e uma de erro, e identificador de correlação em todos os logs.

A. Logs estruturados em JSON com `correlationId`, verificação de saúde e métricas de taxa de requisições, erros e latência expostas localmente, sem rastreamento distribuído por enquanto.
B. Como A, mais rastreamento distribuído com OpenTelemetry desde a U1.
X. Other (please specify)

[Answer]: A

## Q7. [Segurança] Como fica a proteção dos dados em trânsito e em repouso no ambiente local?

O endereço de carteira é tratado como dado pessoal (A5). Hoje só existe o ambiente local no Windows.

A. No ambiente local, sem TLS (o navegador trata `localhost` como contexto seguro, então o cookie `Secure` funciona) e sem criptografia de disco; TLS 1.2+ e criptografia em repouso passam a ser obrigatórios antes de qualquer ambiente compartilhado (G1).
B. TLS com certificado autoassinado já no ambiente local.
X. Other (please specify)

[Answer]: A

## Q8. [Segurança] Como usar o SDK Solana sem levar geração de chaves para a produção?

Seguimento de Q4 (SDK Solana para Java). O `project.md` proíbe incluir no código de produção APIs de importação ou geração de chave secreta ou de frase mnemônica, e os SDKs Solana para Java trazem essas APIs (criar par de chaves, importar mnemônica).

A. O SDK fica só no código de teste, onde gera a carteira do smoke; a produção confere a assinatura com Ed25519 nativo do Java 21 e uma decodificação Base58 pequena (como a opção A de Q4).
B. O SDK entra na produção só para conferir a assinatura, e uma regra automática na CI falha o build se o código de produção chamar qualquer API de geração ou importação de chave do SDK.
C. Trocar Q4 pela opção A (Ed25519 nativo, sem SDK em lugar nenhum).
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Desempenho: na API, p95 até 300 ms para desafio, verificação e primeira página da lista com 20 usuários simultâneos no ambiente local; na tela, a lista aparece em até 2 s (p95), conforme o NFR3 (Q1).
- Limite de tentativas com contadores na memória do processo de API na U1; Redis só quando houver mais de uma instância (Q2).
- Ferramentas de base: Liquibase, Jest, Playwright, Spotless com palantir-java-format, Gitleaks, CodeQL e OWASP Dependency-Check (Q3).
- SDK Solana para Java só no código de teste, gerando a carteira do smoke; a produção confere a assinatura com Ed25519 nativo do Java 21 e uma decodificação Base58 pequena, sem APIs de geração ou importação de chave (Q4, Q8).
- O app conecta a carteira pelo padrão de carteiras Solana (Wallet Standard) com o adaptador oficial; a carteira de teste se registra pelo mesmo padrão (Q5).
- Observabilidade: logs JSON com `correlationId`, verificação de saúde e métricas de requisições, erros e latência; sem rastreamento distribuído por enquanto (Q6).
- Ambiente local sem TLS e sem criptografia de disco; TLS 1.2+ e criptografia em repouso obrigatórios antes de qualquer ambiente compartilhado (Q7).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
