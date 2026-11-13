# Perguntas do Deployment Execution

Conversa: português (Brasil). Alvo: o único ambiente existente, Docker Compose local (`operation/environment-provisioning/environment-inventory.md`). Não há AWS, então a checagem de identidade da AWS não se aplica.

## Pergunta 1
Como fazer a release local deste intent?

A) Commit e push de tudo desde o Build and Test, CI e `security.yml` verdes, tag `v2026.11.12-1`, ensaio de rollback para `v2026.11.08-1` (antes de qualquer anonimização real), subida e `verify.sh` (recomendado)
B) Release sem ensaio
C) Adiar a release
X) Other (please specify)

[Answer]: A

## Pergunta 2
O `security.yml` falhou com `401 Unauthorized` do OSS Index. Seguir cadastrando as credenciais gratuitas como segredo ou desligar o analisador?

A) Cadastrar as credenciais do OSS Index como segredo e passar ao plugin
B) Desligar o analisador OSS Index e ficar só com a base da NVD
X) Other (please specify)

[Answer]: B (resposta do responsável: "Desligar o analisador")

## Pergunta 3
O agente não pode chamar o `bash scripts/verify.sh` diretamente. Rodar o smoke local da release?

A) O responsável roda `! bash scripts/verify.sh`
B) Seguir sem o `verify.sh` local
X) Other (please specify)

[Answer]: B (resposta do responsável: "Siga sem verify.sh"). O smoke da release é a execução do mesmo `bash scripts/verify.sh` pela CI no commit da tag (jobs `smoke` e `e2e` da execução `43214445497`, 19 de 19 nos dois).
