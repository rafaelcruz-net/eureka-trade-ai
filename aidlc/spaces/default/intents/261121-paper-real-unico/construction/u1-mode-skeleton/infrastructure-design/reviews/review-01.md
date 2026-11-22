## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-21
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/infrastructure-design/infrastructure-specification.md > "Chave da semente" (linha de `MEMETRADE_REAL_SEED_TEST_RELEASE`) e cicd-pipeline.md > "Mudanças no scripts/verify.sh", item 1 | O desenho afirma que `MEMETRADE_REAL_SEED_TEST_RELEASE` nunca aparece em `.env.example`. O arquivo real tem a linha comentada `# MEMETRADE_REAL_SEED_TEST_RELEASE=` (linha 133, bloco "api and worker"). A guarda do `verify.sh` e o `RealSeedProfileGuardTest`, com a regra descrita, falham no primeiro run, ou então a guarda precisa ignorar comentários, o que o desenho não diz. | Decidir e registrar: ou a Code Generation remove essa linha do `.env.example` (preferível, a semente é só do compose de teste), ou a regra da guarda ignora linhas comentadas. Escrever a regra exata no texto pronto do `verify.sh`. | New |
| R-02 | Major | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/infrastructure-design/infrastructure-specification.md > "Configuração nova" (nomes das variáveis) | Os nomes `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT` etc. (sem `_` entre as palavras) só ligam às chaves com hífen se a classe de configuração usar `@ConfigurationProperties` (relaxed binding). Com `@Value` ou `Environment.getProperty`, o mapeamento do ambiente só troca `.` e `-` por `_`, e a variável não liga. O precedente citado (`MEMETRADE_REAL_ENABLED`) não tem hífen, então não prova o caso. Na pior hipótese, o Terraform grava um valor que nunca chega ao app, em silêncio. | Exigir `@ConfigurationProperties` para as três chaves (como `BotProperties`) e um teste de integração que suba o contexto com cada uma das três variáveis e confira o valor lido (incluindo valor fora da faixa que impede a subida). Registrar isso como critério da Code Generation. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/infrastructure-design/infrastructure-specification.md > "Regras da configuração", linha "Só no api_environment" | O worker não recebe as variáveis e usa o padrão. O `RealCreationEligibility` (timeout de 500 ms) existe nos dois processos porque o `BotCommandService` existe nos dois, mas só é chamado na API (`logical-components.md`, "Por processo"). Hoje é seguro. Se o fundador mudar o valor só no Terraform da API, o worker fica com outro valor e ninguém percebe. | Anotar que o worker não chama a elegibilidade e que, se passar a chamar, a variável entra também no `worker_environment`. Um teste ArchUnit ou de contexto no worker, conferindo que não há chamada, é opcional. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/infrastructure-design/infrastructure-specification.md > "Assumptions & Open Questions", item 2, e traceability.json > NFR8.1 | Conflito NFR8.1 ("0 mudanças nas definições de tarefa") contra Q2 (três variáveis). A saída é aceitável: a revisão da tarefa não cria recurso, CPU, memória, contêiner nem segredo, e o valor é igual ao padrão. Mas o `scalability-requirements.md` e o `performance-design.md` ("fixos e nunca lidos do ambiente") continuam contradizendo, e o `traceability.json` marca NFR8.1 como OK com a ressalva só dentro do texto do alvo. | Levar o conflito ao gate como decisão explícita do fundador, e na Code Generation ajustar o texto de NFR8.1 e do `performance-design.md` para "0 recursos novos; 3 variáveis sem segredo na definição de tarefa existente". | New |
| R-05 | Minor | aidlc/spaces/default/intents/261121-paper-real-unico/construction/u1-mode-skeleton/infrastructure-design/traceability.json > NFR3.10 e NFR4.6 | Os alvos dizem "lido pelo sidecar metrics-agent a cada 120 s", mas a lista de permissão descarta as duas séries. Na nuvem, a evidência vem dos logs, não das métricas. O alvo induz a ler que o contador chega ao CloudWatch. | Reescrever os dois alvos: série em `/actuator/prometheus` (8081 e 8082) e evidência na nuvem pelas mensagens de log `REAL bot creation refused` e `P&L block unavailable` no CloudWatch Logs. | New |

### Veredito sobre os dois conflitos da plataforma

- **NFR8.1 contra Q2:** aceitável, sem mudança de desenho. É um ajuste de texto, não de arquitetura. Os valores do Terraform são iguais aos padrões; a ordem entre `apply` e imagem não importa; e o rollback é seguro, porque a imagem anterior ignora as variáveis que não conhece. O valor inválido derruba só a nova tarefa e o circuit breaker devolve a revisão. As variáveis não são flags do REAL, então a regra de "flag do REAL é implantação separada" não se aplica. Condicionado ao segundo Major (a ligação precisa mesmo funcionar).
- **Contadores fora da lista de permissão:** aceitável. A economia é de cerca de US$ 2,10 por conta por mês, e NFR8.5 só pede que as métricas fiquem disponíveis para um alarme futuro, o que continua verdade (basta uma entrada em `app_metric_declarations`). Nenhum alarme depende delas; os alarmes `metrics-absent-*` e de 5xx continuam cobrindo a U1 de forma indireta. Só falta corrigir a redação do traceability (último Minor).

### Verificações sem achado

- **Nenhuma infraestrutura nova:** confirmada. Só o mapa `api_environment` de `infra/terraform/main.tf` muda, e os padrões das chaves ainda não existem no `application.yml` (a Code Generation os cria).
- **U1 nunca num ambiente com usuários antes da U2:** coerente entre a especificação, o `cicd-pipeline.md` (tabela de ambientes, mainnet só com a U2 no mesmo SHA) e o `team.md`. O `deploy.yml` ainda não existe, então nada na nuvem acontece.
- **Chave da semente:** o `application.yml` tem o literal `false` (linha 138). Fora do `.env.example` comentado, só aparece em `infra/docker-compose.real.yml` e em `scripts/load/real.mjs` (mensagem de erro, fora do escopo da guarda). A barreira `LOCALNET` somada ao `MEMETRADE_REAL_NETWORK = local.network_upper` na nuvem é uma segunda camada válida.
- **CI sem mudança:** os jobs listados batem com o `team.md`. O `verify.sh` real termina o `playwright test` em `e2e/legal-documents` e acrescenta mais specs depois. O desenho cita "hoje termina em `e2e/real-v2-exits`", o que pode estar desatualizado. Conferir o fim real da lista ao entregar o texto pronto ao responsável.
- **Rastreabilidade:** 25 IDs, todos OK, alvos concretos (arquivo mais elemento). Só os dois alvos acima induzem a erro.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Conferência manual de `infra/terraform/main.tf`, `application.yml`, `.env.example`, `scripts/verify.sh` | Duas divergências (`.env.example`, ligação das variáveis); fim da lista de specs do `verify.sh` pode estar desatualizado | Ver os dois Major e a nota sobre o `verify.sh` |
| Contagem de IDs do `traceability.json` | 25 em `upstream_ids`, 25 em `coverage`, todos OK | Completo |

### Summary

O desenho cumpre "sem infraestrutura nova" e protege a semente e a promoção para a mainnet. Os dois conflitos sinalizados são aceitáveis. Dois Major, corrigíveis na Code Generation sem mudar o desenho, pedem atenção: a guarda da semente colide com a linha existente do `.env.example`, e o relaxed binding exige `@ConfigurationProperties` com teste. Com no máximo 2 Major e nenhum Critical, o veredito é READY.
