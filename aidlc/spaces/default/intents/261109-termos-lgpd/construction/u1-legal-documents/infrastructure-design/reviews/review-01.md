**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-09T17:48:15Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/infrastructure-design/infrastructure-specification.md > Deployment > Rede e tabela "Configuração nova da `api`" (`MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS` = `172.28.0.0/16`) | A isenção cobre a faixa inteira da rede do Compose. O gateway da ponte (por exemplo 172.28.0.1) também está nessa faixa, e é a partir dele que o `api` costuma enxergar o tráfego que chega pelas portas publicadas em `127.0.0.1` (navegador, Playwright do `verify.sh`, k6 do `load-test.yml`). Com isso o tráfego de fora do Compose fica isento no ambiente local, e o teste "X-Forwarded-For forjado recebe 429" do design de NFR não pode passar no e2e. O limitador fica sem efeito justamente no caminho que a verificação exercita. | Atribuir IPs fixos (`ipv4_address`) a `web` e `admin` numa sub-faixa estreita (por exemplo `172.28.1.0/24`) e listar só essa sub-faixa, deixando o gateway de fora. Alternativamente, documentar e testar explicitamente que o par direto do tráfego publicado cai fora da lista. Acrescentar ao e2e ou ao teste de integração uma verificação de que uma requisição pela porta publicada recebe 429 ao exceder o limite. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/infrastructure-design/infrastructure-specification.md > Deployment > Rede | A faixa fixa `172.28.0.0/16` é um valor literal no Compose, sem como trocá-la. Redes Docker existentes na máquina do desenvolvedor (Windows) ou na CI podem usar a mesma faixa e a subida falha com "Pool overlaps". Além disso, `ComposeFilesTest` valida o Compose mesclado, e o bloco novo de `networks` exige ajuste desse teste, que não é citado. | Parametrizar a sub-rede por variável com valor padrão (por exemplo `${COMPOSE_SUBNET:-172.28.0.0/16}`) e manter a lista de isenção coerente com ela. Registrar a atualização do `ComposeFilesTest` e do `infra/docker-compose.test.yml` como tarefa de geração de código. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/infrastructure-design/cicd-pipeline.md > Pré-requisitos e linha "carga (manual)" | O cenário `legal` precisa entrar na lista `options` e na descrição do `load-test.yml` (hoje: u1, u4, u3, growth, u7, u7-jobs, u6, u5), e o driver de carga roda do host. Como o `load-test.yml` é um workflow, só o responsável do projeto o altera, mas o pipeline lista só `ci.yml` e `verify.sh` como pré-requisitos. | Acrescentar o `load-test.yml` (nova opção `legal`) aos pré-requisitos do responsável do projeto. Indicar também que o limite alto do perfil `loadtest` vem de `application-loadtest.yml`, pois o overlay de teste não repassa as variáveis `MEMETRADE_LEGAL_*`. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u1-legal-documents/infrastructure-design/infrastructure-specification.md > "Configuração nova da `api`" | Os nomes `MEMETRADE_LEGAL_*` seguem o binding relaxado do Spring (`rate-limit.internal-networks` e `rate-limit-per-minute` casam com as variantes com sublinhado), mas a tabela não diz que o `docker-compose.yml` repassa cada variável com `${VAR:-}` nem que a lista CIDR separada por vírgula é lida como `List`. Hoje o `docker-compose.yml` não repassa nenhuma delas. | Acrescentar ao design que cada variável é repassada em `docker-compose.yml` e listada em `.env.example`, e que um teste de configuração confirma o binding de `MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS` para `memetrade.legal.rate-limit.internal-networks`. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Revisão manual de `traceability.json` contra as NFRs do design | PASS | 14 NFRs cobertas, cada uma com alvo concreto |
| Verificação de segredos nas variáveis novas | PASS | Nenhum segredo e nenhuma `NEXT_PUBLIC_*` |

### Summary

O design cobre as NFRs com recursos concretos, mantém os portões da CI consistentes com as práticas do time e define o rollback pela imagem anterior. O risco principal é a isenção do limitador por uma faixa `/16` que inclui o gateway da ponte do Docker e pode isentar o tráfego das portas publicadas. Isso é um Major e não bloqueia, mas deve ser corrigido na geração de código.
