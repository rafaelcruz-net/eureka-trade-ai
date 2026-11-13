# Ciclo de retorno

Conversa: português (Brasil). Resposta 4A e 5A. Entradas para o próximo ciclo de ideação, a partir das evidências deste intent: `operation/observability-setup/` (alarmes, painéis, SLOs), `operation/deployment-execution/deployment-log.md`, `operation/performance-validation/test-results.md` e `operation/incident-response/incident-plan.md`.

## Pendências com prazo ou que bloqueiam o beta

| Item | Prazo ou bloqueio | Dono |
|---|---|---|
| Migrar para o Spring Boot 4 (Spring 7.0.9+) | antes de 2027-02-09, quando EX-001 a EX-012 vencem e voltam a bloquear o merge | responsável do projeto |
| Nomear o encarregado e preencher os campos de implantação dos documentos legais | bloqueia publicar a versão do beta dos termos e da política | responsável do projeto, com o parecer C-09 |
| Publicar a versão v2 real dos termos | depois dos campos de implantação; ativa o novo aceite para todos | responsável do projeto |
| Conta da AWS, staging e produção do beta PAPER | bloqueia SLOs reais, alarmes no CloudWatch e a validação de desempenho num ambiente parecido com produção | responsável do projeto (C-10, C-12) |
| `perf-audit.sh` da U2 com volume real | antes do G1 | responsável do projeto |
| Medir de novo a trava de novo aceite (84% da meta) | antes do G1 | — |

## Lições de operação deste intent

1. **Confirmar que a CI disparou.** Até 2026-11-11, nenhum push para `main` tinha rodado a CI, e ninguém percebeu. Depois de cada push, conferir com `gh run list`.
2. **A primeira execução no Linux revela o que o Windows esconde.** Ordem de classes de teste, estado compartilhado do banco de teste e o streaming do Next.js derrubaram testes que passavam localmente. Testes instáveis foram corrigidos na causa, nunca desligados.
3. **A varredura de dependências nunca tinha aplicado as exceções**, por um caminho relativo errado no `security.yml`. Um arquivo de exceções que ninguém lê é pior que nenhum.
4. **Uma meta de latência medida logo depois da subida mede o custo frio.** Pool fixo e aquecimento resolveram sem mexer na meta.

## Trabalho manual a automatizar

| Hoje | Proposta |
|---|---|
| Conferir à mão se a CI disparou depois do push | um passo do processo de commit (ou um hook local) que espera a execução aparecer |
| Lembrar do vencimento das exceções de segurança | o `SecurityExceptionsTest` já bloqueia quando vence; acrescentar um aviso na CI 30 dias antes |
| Patches de workflow e `verify.sh` local pelo responsável | continuam manuais por decisão (arquivos protegidos) |
