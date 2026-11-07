# Instruções de testes — U6 Barreira de limites

Como preparar, rodar e medir os testes da Unit `u6-risk-control`, conforme o [plano de geração de código](code-generation-plan.md).

- **Estratégia:** a do workflow é `Comprehensive`: 10 a 15 testes por componente, com unitários e de integração, mais testes de desempenho e de segurança onde os NFRs pedem.
- **Ponta a ponta:** fica com a U5 (Q1 da Infrastructure Design).
- **Piso de cobertura:** os 80% de linhas do escopo `enterprise` somam-se à estratégia.
- **Testes antes do código:** a U6 é núcleo financeiro do `team.md` (limites de risco e deduplicação). Banco, repositório, lógica e API têm os testes escritos antes do código.
- **Testes depois:** configuração, saúde, carga e documentação seguem implementar e depois testar.

## Configuração dos executores

| Camada | Ferramentas | Configuração |
|---|---|---|
| Backend | JUnit 5, AssertJ, Testcontainers (`postgres:17-alpine`), ArchUnit, JaCoCo | `apps/api/pom.xml` existente: Surefire (`*Test`), Failsafe (`*IT`) e a regra `check` do JaCoCo com 80% de linhas no `verify` completo. Marcador `u6` |
| Base de integração | `AbstractU6IT` sobre `AbstractPostgresIT` (U2) | Mesmas migrações, inclusive a `0009`, e o `10-roles.sql` aplicado. Conexões extras com o papel da aplicação e com o login de retenção para os testes de privilégio |
| Carga | `scripts/load-test.sh` com `scripts/load/load-test.mjs` | Cenário `u6`; só local |

Pré-requisitos: Docker Desktop em execução, JDK 21 e Git Bash no Windows.

## Comandos desta Unit

| O que roda | Comando exato |
|---|---|
| Backend da U6, unitários e integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u6 test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Backend da U6, uma classe de integração | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u6 -Dit.test=OrderGateIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test failsafe:integration-test failsafe:verify` |
| Catálogo E1 a E21 | `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u6 -Dtest=RiskGateCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test` |
| Cobertura da suíte inteira (piso de 80%) | `apps/api/mvnw -f apps/api/pom.xml verify` |
| Carga da U6 (só local) | `LOAD_SCENARIO=u6 SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` |
| Volta 1: regressão da conferência de plano | `node --test scripts/load/load-test-u6-plans.test.mjs` |
| Volta 1: sintaxe do script de carga | `node --check scripts/load/load-test.mjs` |
| Volta 1: testes que leem o script e o Compose | `apps/api/mvnw -f apps/api/pom.xml -Dtest='WorkflowsTest,ComposeFilesTest' -Dsurefire.failIfNoSpecifiedTests=false test-compile surefire:test` |

Na volta 1 de Build and Test (Passos 19 a 21), o teste `node --test` é escrito antes da correção e falha porque a consulta do `EXPLAIN` ainda tem a subconsulta de amostra. Depois da correção, ele passa. O cenário de carga `u6` roda de novo no Build and Test, não nesta etapa.

O comando do backend da U6 fica pronto no Passo 2, antes do primeiro teste vermelho (Passo 4). Nos passos com testes antes do código, a saída vermelha de cada passo é registrada no resumo do código antes da implementação.

## Metas de cobertura

- 80% de linhas no backend, medidos sobre a suíte inteira.
- As exclusões ficam fixas, só na configuração da ferramenta. A U6 não acrescenta nenhuma. O pacote `riskcontrol.loadtest` é coberto pelo teste que confirma que os beans dele só existem com o perfil `loadtest`.
- O piso nunca é reduzido para fazer um passo passar.

## Testes por componente

| Componente | Tipo | Quantidade prevista | Casos de risco obrigatórios |
|---|---|---|---|
| Configuração e guarda do agendador | Unitário | 4 | Pool abaixo do mínimo recusado |
| Migração `0009`, privilégios e papéis | Integração | 15 | Registros sem `UPDATE`; `DELETE` só de aprovada antiga por `risk_retention`; só PAPER; aplicação fora do grupo |
| Verificações (`LimitChecks`) | Unitário (catálogo) | 25 | E1 a E19: fronteira e incremento; `unknown` nunca zero; ordem dos motivos; gatilhos |
| Slippage e queda do patrimônio | Unitário | 10 | Caso-base 3,600000; venda com queda 0 |
| Propriedade | Propriedade | 1 × 1.000 combinações | Nenhuma aprovada acima de limite |
| Repositórios | Integração | 12 | `ON CONFLICT` sem abortar; filtro por tenant e pessoa |
| `OrderGate.evaluate` | Integração | 15 | Transação exigida; um evento por recusa; contexto do dono |
| Duplicidade | Integração | 3 × 50 repetições | E8, E20 em `READ COMMITTED`, E21 |
| Falha fechada | Integração | 10 | Falha em cada leitura e gravação; nada gravado; U7 não chamada |
| Conferência com a U7 | Integração | 6 | Política vigente; slippage e queda iguais aos da U7 |
| Expurgo e pool de retenção | Integração | 12 | 29, 30,5 e 32 dias; aprovada citada nunca sai; decisão antiga contida pela U7 |
| Rota C21 | Integração e contrato | 15 | Os cinco casos de 404; `400`; `403` com outro modo; só `GET` |
| Saúde, métricas e logs | Integração | 8 | Fora da prontidão; nada sensível em log e métrica |
| Arquitetura | ArchUnit | 8 | Sem `double`/`float`; sem rede; sem `ZERO` no lugar de `Unknown`; pool de retenção só no job |
| Carga | `scripts/load-test.sh` | 1 execução | p95 de `evaluate` e da C21; lote do expurgo |

## Mocks e dublês

- **Relógio:** `MutableClock` em todo teste de frescor, intervalo, dia UTC e expurgo. O expurgo grava `evaluated_at` explícito, porque o corte é do `now()` do banco.
- **Dado de mercado:** dublê da C11 com preço, liquidez, horário de observação e `unknown` sob comando, sem acesso de rede.
- **Visão de risco:**
  - nos testes unitários, registros montados à mão;
  - nos de integração, a `PortfolioRiskView` real da U7 sobre contas criadas com `fund` e ordens executadas, mais um dublê para patrimônio vazio e valores de fronteira.
- **Transação do ciclo:** os testes abrem a transação como a U5 fará (`TransactionTemplate` em `READ COMMITTED`) e ligam o contexto do dono, porque a U5 ainda não existe.
- **Falha técnica:** dublês que lançam exceção em cada leitura e repositórios de teste que lançam em cada gravação.
- **Banco:** PostgreSQL real em contêiner, com as migrações. Cada teste cria os próprios dados. Os testes de concorrência usam conexões próprias e uma barreira de início.
- **Publicador da trilha:** o real da U2, conferido pela tabela de saída.

## Dados de teste

- Mints, bots, decisões e pessoas são sintéticos, gerados no teste. Nada é real.
- Os valores do catálogo E1 a E21 ficam escritos no teste como texto, copiados da functional-spec.md.
- O teste de propriedade usa uma seed fixa.
- Teste instável é tratado como defeito e corrigido, nunca ignorado.
