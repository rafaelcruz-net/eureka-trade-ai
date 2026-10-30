# Decisões de tecnologia — U1 Esqueleto de ponta a ponta

Tecnologias que a Unit `u1-walking-skeleton` estabelece para o projeto inteiro, para implementar os fluxos de [functional-spec.md](../functional-design/functional-spec.md) e as regras de [rules.md](../functional-design/rules.md). A stack principal vem do pedido inicial e das restrições de [requirements.md](../../../inception/requirements-analysis/requirements.md); os contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md) definem OpenAPI gerado do código e tipos gerados no frontend. As escolhas novas vêm das respostas Q2 a Q8 de [nfr-requirements-questions.md](nfr-requirements-questions.md). Versões exatas são fixadas nos lockfiles na geração de código.

## Stack

| Camada | Escolha | Por quê | Alternativa rejeitada |
|---|---|---|---|
| Backend | Java 21 com Spring Boot, monólito modular por pacote, Maven | Stack prescrita; Ed25519 nativo no Java 21 | — (prescrita) |
| Frontend | Next.js com React e TypeScript em modo `strict`, pnpm, Node LTS fixado | Stack prescrita | — (prescrita) |
| Banco | PostgreSQL | Stack prescrita; transações para a tabela de saída e o consumo do nonce | — (prescrita) |
| Migrações | Liquibase | Escolha da Q3; mesmas migrações na aplicação e nos testes de integração | Flyway |
| Contrato | OpenAPI gerado do código do backend; tipos do frontend gerados dele; CI compara gerado e versionado | Q2 do Contract Design | Contrato escrito à mão |
| Conferência de assinatura | Ed25519 nativo do Java 21 com decodificação Base58 própria e testada | Sem dependência criptográfica extra e sem APIs de chave na produção (Q4, Q8) | SDK Solana na produção (proibido pelo `project.md`); BouncyCastle |
| SDK Solana para Java | Só no escopo de teste, para gerar a carteira do smoke | Q4 e Q8 | Carteira fixa versionada (chave no repositório) |
| Carteira no navegador | Padrão de carteiras Solana (Wallet Standard) com o adaptador oficial | Phantom e Solflare pelo mesmo caminho; a carteira de teste se registra igual (Q5) | APIs injetadas de cada carteira |
| Contadores do limite de tentativas | Memória do processo de API | Uma instância no beta local (Q2) | Redis desde já |
| Ambiente local | Docker Compose no Windows | Stack prescrita | — |

## Qualidade e segurança

| Assunto | Escolha | Origem |
|---|---|---|
| Testes do backend | JUnit 5, Testcontainers com PostgreSQL real | `team.md` |
| Cobertura do backend | JaCoCo com regra `check` de 80% de linhas | NFR12, `team.md` |
| Testes do frontend | Jest com limite de cobertura de 80% de linhas | Q3, NFR12 |
| Ponta a ponta | Playwright | Q3 |
| Formatação Java | Spotless com palantir-java-format | Q3 |
| Lint e formatação do frontend | ESLint e Prettier | `team.md` |
| Segredos | Gitleaks no pré-commit e na CI | Q3, NFR6.7 |
| SAST | CodeQL | Q3, NFR6.8 |
| Dependências | OWASP Dependency-Check | Q3, NFR6.8 |
| Observabilidade | Logs JSON, verificação de saúde e métricas pelo suporte do próprio framework, sem rastreamento distribuído | Q6 |
| CI | GitHub Actions com ações fixadas por SHA e `permissions` mínimas | `team.md` |

## Requisitos de qualidade

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR12.1 | Piso de cobertura | 80% de linhas no backend e no frontend, medidos separadamente; exclusões listadas na configuração da ferramenta | NFR12 |
| NFR12.2 | Testes obrigatórios por risco que a U1 toca | Login sem reutilização de assinatura, `unknown` nunca zero, isolamento por tenant nas rotas existentes, REAL inexistente no contrato | NFR12, `team.md` |

## Pendências para o NFR Design

- Intervalo exato do relay da auditoria (proposto: 1 s) e da coleta simulada (proposto: 30 s ou menos).
- Retenção das respostas de idempotência fora do desafio (provisório: 24 horas, OQ-6 do Contract Design).
