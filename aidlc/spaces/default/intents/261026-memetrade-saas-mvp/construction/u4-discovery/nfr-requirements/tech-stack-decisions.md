# Decisões de tecnologia — U4 Descoberta e avaliação

A U4 usa a stack prescrita do projeto (Constraints de [requirements.md](../../../inception/requirements-analysis/requirements.md)) e as escolhas já feitas na U1 e na U2. Há uma biblioteca nova (Resilience4j, Q4) e nenhuma mudança de linguagem, framework ou banco.

| Área | Escolha | Situação | Justificativa |
|---|---|---|---|
| Linguagem e framework do backend | Java 21, Spring Boot 3.5 (módulos `marketdata`, `scanner` e `riskassessment` por pacote) | Mantida | Stack prescrita; monólito modular (U1) |
| Acesso a dados | `spring-boot-starter-jdbc` com `JdbcClient` e SQL parametrizado | Mantida | Padrão da U1 (desvio registrado lá); consultas da lista feitas à mão para controlar o plano |
| Banco | PostgreSQL 17 | Mantida | Stack prescrita; decimal exato (`numeric`) para valores de mercado, pesos e cobertura |
| Migrações | Liquibase, aplicado pelo `db-migrate` com o dono do esquema | Mantida | Padrão da U2; a U4 acrescenta tabelas de histórico, versões de pesos, avaliações e marca de fontes, e a versão 1 dos pesos |
| Execução única dos trabalhos agendados | ShedLock com provedor JDBC | Mantida | Padrão da U2 para coleta por fonte, recálculo e limpeza (NFR2.7, NFR10.7) |
| Tempo limite e novas tentativas nas fontes | **Resilience4j** (módulos `retry` e `timelimiter`), versão fixa compatível com Spring Boot 3.5 e Java 21, escolhida na geração de código com pelo menos duas semanas de publicação | **Nova** (Q4) | Biblioteca madura para espera crescente com variação aleatória e tempo limite; coberta pelo OWASP Dependency-Check e pelo CodeQL (NFR6.7). Alternativa rejeitada: código próprio (menos dependência, mais código a testar) |
| Limite de requisições por pessoa | Contador em memória no processo da API, janela deslizante de 60 s, sem biblioteca nova | Nova (implementação própria) | Uma instância no beta (NFR2.8); evita outra dependência; revisto com a hospedagem. Alternativa rejeitada: Bucket4j (dependência a mais para um caso simples) |
| Relógio | `Clock` injetável | Mantida | Testes de frescor, janela de 1 hora e retenção (team.md, Determinismo) |
| Métricas e saúde | Micrometer com Prometheus, Spring Boot Actuator | Mantida | Padrão da U1 e da U2 (observability-requirements.md) |
| Testes do backend | JUnit 5, AssertJ, Testcontainers (PostgreSQL), ArchUnit, JaCoCo (80% de linhas); testes de propriedade com semente fixa para o indicador | Mantida | team.md (Testing Posture); NFR4.1 |
| Frontend | Next.js com TypeScript `strict`, tipos gerados do OpenAPI do convidado | Mantida | Padrão da U1; o C2 ganha os acréscimos listados em functional-spec.md |
| Testes do frontend | Jest e Testing Library; Playwright para o smoke da tela do scanner | Mantida | Padrão da U1 |
| Carga local | Script `scripts/load-test.sh` com `scripts/load/load-test.mjs`, ampliado para a lista e o detalhe | Mantida e ampliada | Medição do NFR3.1 e NFR3.2 sem ferramenta nova |
| Adaptadores de mercado | Duas implementações simuladas (A e B) da porta `MarketSourceAdapter`, identificadas com `simulated = true`, com fixtures gravadas nos testes | Mantida e ampliada | C19, Q2; fontes reais só depois do G0 (C-06) |

## Restrições que continuam valendo

- Nenhum ponto flutuante em valores de mercado, pesos, cobertura, variação ou indicador (`project.md`).
- Instantes em UTC; conversão para o fuso da pessoa só na tela (`project.md`).
- Nenhum SDK Solana no código de produção (`project.md`, Forbidden).
- Ações de terceiros na CI fixadas por SHA; lockfiles versionados (`project.md`).
