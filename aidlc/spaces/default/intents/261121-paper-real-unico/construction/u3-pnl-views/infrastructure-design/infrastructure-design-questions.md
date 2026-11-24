# Perguntas do Infrastructure Design — U3 `u3-pnl-views`

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes:
- `construction/u3-pnl-views/nfr-design/`: executor de auditoria `pnl-read-audit`, filtro em memória, contadores e ampliação do cenário de carga;
- `construction/u3-pnl-views/nfr-requirements/`: NFR1.6 (9 leituras, 7 portões) e NFR8;
- `construction/u2-real-bot-pnl/infrastructure-design/`: pool `pnl-mismatch-audit`, lista de permissão e texto pronto do `load-test.yml`;
- `team.md`, seção Deployment.

## Q1. Implantação e configuração

O que muda na implantação e na configuração?

A. Nada de infraestrutura nova:
   - nenhuma migração;
   - nenhuma variável de ambiente nova;
   - nenhuma mudança na definição de tarefa, porque os três contadores novos ficam fora da lista de permissão do CloudWatch e o sidecar não muda.

   O executor `pnl-read-audit` (1 thread, fila de 16) e o filtro em memória usam constantes testadas, dentro da tarefa da API que já existe, e reaproveitam o pool `pnl-mismatch-audit` de 2 conexões. A conta de conexões não muda. O rollback é só da imagem. (recomendado)
B. Pool próprio para a U3
X. Other (please specify)

[Answer]: A

## Q2. Monitoramento

O que muda no monitoramento?

A. Os três contadores novos (`pnl_compare_template_mismatches_total`, `pnl_compare_side_unavailable_total{market,cause}` e `pnl_read_audit_failures_total{action}`) aparecem só no `/actuator/prometheus`. Não há alarme novo, nem regra nova, nem entrada na lista do CloudWatch. Na nuvem, os eventos de auditoria e os logs JSON servem de evidência. (recomendado)
B. Alarme para as recusas de comparação
X. Other (please specify)

[Answer]: A

## Q3. CI e carga

O que muda na CI e no teste de carga?

A. Os jobs da CI não mudam. O spec `zz-paper-real-unico` ampliado já roda no `verify.sh`, sem linha nova. O texto pronto do `load-test.yml` da U3, com 9 leituras e 7 portões, substitui o da U2 no `code-summary.md` da U3, e a U2 ganha uma linha apontando para ele. O responsável aplica esse texto uma vez só, e o Build and Test da U2 e da U3 fecha com uma execução verde do `pnl-by-mode`. (recomendado)
B. Workflow de carga separado para a U3
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Implantação:** nenhuma infraestrutura, migração, variável ou mudança na tarefa. O executor de auditoria usa constantes e reaproveita o pool da U2. O rollback é só da imagem [Q1].
- **Monitoramento:** os três contadores ficam só no Prometheus, sem alarme e sem CloudWatch [Q2].
- **CI e carga:** os jobs não mudam. O texto do `load-test.yml` da U3 substitui o da U2, e você o aplica uma vez só. O Build and Test da U2 e da U3 fecha com uma execução verde do `pnl-by-mode` [Q3].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
