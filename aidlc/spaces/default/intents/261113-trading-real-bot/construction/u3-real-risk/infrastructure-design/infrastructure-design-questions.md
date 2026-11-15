# Perguntas de Infrastructure Design — U3 `u3-real-risk`

Conversa: português (Brasil). Base:
- `construction/u3-real-risk/nfr-design/`: componentes lógicos, confiabilidade, escala e observabilidade;
- `construction/u2-real-market-data/infrastructure-design/`: monitoramento com regras do `promtool` e alarmes no Terraform, mais o teste de paridade;
- `construction/u1-bot-wallet/infrastructure-design/`;
- `team.md` (Deployment).

## Q1. [Recursos] O que a U3 acrescenta na nuvem e no local?

A. Nenhum recurso novo. A barreira roda nos processos da API e do worker que já existem, e as tabelas entram pela migração Liquibase, aplicada pelo passo de migração atual. Os limites (`memetrade.real.risk.*`) ficam com os padrões e, em `.env.example`, só aparecem comentados. O Compose local e o `verify.sh` não mudam.
B. Um serviço próprio para a barreira
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Alertas] Onde ficam os alertas da U3?

A. Como na U2: as regras do Prometheus ficam em `infra/observability/real-risk-alerts.yml`, conferidas pelo `promtool`. Os alarmes equivalentes ficam no módulo `observability` do Terraform, com as rotas da U1 (crítica e alta por SNS com e-mail e SMS; média e aviso por e-mail). Um teste de paridade compara nome, limite e janela. O caminho das métricas até o CloudWatch continua como pendência única de U1, U2 e U3, a decidir antes da mainnet.
B. Só regras do Prometheus, sem Terraform
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [CI] O que muda na CI?

A. Nada no `ci.yml`. Os testes da U3 rodam no job `backend` que já existe, com piso de 80% e ArchUnit. O arquivo de regras novo entra na etapa do `promtool` que o responsável do projeto vai acrescentar (texto pronto no code-summary da U2), cobrindo todos os arquivos de `infra/observability/`.
B. Um job novo para a U3
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Implantação e rollback] Como implantar e voltar?

A. Ordem: migração (só cria tabelas, gatilho e permissões), depois worker e API. Como a U4 ainda não chama a M5, a barreira entra sem efeito em ordem nenhuma. Rollback: desligar o REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) e voltar a imagem; as tabelas ficam (expand-contract). O saque continua disponível o tempo todo.
B. Remover as tabelas no rollback
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Nenhum recurso novo: a barreira roda na API e no worker, e as tabelas vêm por migração. O local e o `verify.sh` não mudam [Q1].
- Os alertas seguem o padrão da U2: regras do `promtool`, alarmes no Terraform com teste de paridade. O caminho até o CloudWatch continua pendente para U1 a U3 [Q2].
- A CI não muda: os testes rodam no job `backend`, e a etapa do `promtool` é a que você vai aplicar [Q3].
- Implantação: migração antes da aplicação; rollback desligando o REAL, sem remover tabelas; saque sempre disponível [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
