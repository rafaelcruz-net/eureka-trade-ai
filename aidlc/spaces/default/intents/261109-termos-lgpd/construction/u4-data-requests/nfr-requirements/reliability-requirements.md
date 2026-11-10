# Requisitos de confiabilidade e acessibilidade — U4 `u4-data-requests`

Conversa: português (Brasil). Fontes: `construction/u4-data-requests/functional-design/` (BR4.5, BR4.12 a BR4.15, BR4.18), `inception/requirements-analysis/requirements.md` (NFR4, NFR5, NFR7) e a regra de acessibilidade básica do beta.

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR7.1 | O expurgo `dataSubjectRequestPurge` roda uma vez por dia às 04:00 UTC, em lotes de 1000. Ele usa o padrão da U2: declaração do prazo, tabela própria `privacy_job_run` (uma linha por job do Privacy, criada na migração `0014`; a `identity_job_run` do Identity não é alterada), recuperação na subida e saúde `DEGRADED` depois de 48 h sem sucesso | testes de unidade com relógio injetável e de integração da recuperação |
| NFR7.2 | O job de 15 minutos é idempotente. Rodar duas vezes não gera dois avisos nem duas recusas, e uma falha não derruba a API | teste de integração |
| NFR5.1 | A migração `0014` só acrescenta: as tabelas `data_subject_request`, `data_subject_verification` e `privacy_job_run`, e os índices. Nenhuma tabela existente muda, e a trilha de auditoria continua sem edição | teste que lê a migração |
| NFR4.1 | As telas T5, T6, T6a, T6b e T6c seguem a acessibilidade básica do beta:<ul><li>só teclado, em ordem, com foco visível;</li><li>"Pular para o conteúdo";</li><li>títulos em ordem e regiões;</li><li>rótulos visíveis;</li><li>contraste legível no tema escuro;</li><li>alertas de prazo com ícone e texto, nunca só a cor;</li><li>mensagens em `aria-live`.</li></ul> | testes de componente e spec de ponta a ponta com teclado; o contraste é conferido na revisão visual do Build and Test |
| NFR4.2 | Os estados de carregando, erro (com "tentar de novo") e vazio são distintos e em texto nas duas telas de lista | teste de componente |
