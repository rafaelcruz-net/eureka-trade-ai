# Desenho de confiabilidade e acessibilidade — U4 `u4-data-requests`

Conversa: português (Brasil). Implementa `construction/u4-data-requests/nfr-requirements/reliability-requirements.md` (NFR7.1, NFR7.2, NFR5.1, NFR4.1, NFR4.2).

| Requisito | Desenho |
|---|---|
| NFR7.1 | `DataSubjectRequestPurgeJob` às 04:00 UTC pelo `PrivacyJobRunner`:<ul><li>apaga a verificação e depois o pedido, em lotes de 1000 por `closed_at <= now - 12 meses`;</li><li>`privacy_job_run` guarda a execução, e o evento `DATA_REQUESTS_PURGED` (só contagens, sem tenant, pelo `system`) sai na mesma transação curta `REQUIRES_NEW`, como no `IdentityJobRunner`; o `PrivacyJobRunner` copia esse padrão no próprio módulo, sem depender do Identity;</li><li>`PrivacyJobRecovery` roda na subida quando o último sucesso tem mais de 24 h;</li><li>o indicador `dataSubjectRequestPurge` fica `DEGRADED` com 48 h sem sucesso.</li></ul>A declaração `RetentionEnforcerDeclaration` entra no Privacy, e `DATA_SUBJECT_REQUESTS` sai da `PendingRetention` |
| NFR7.2 | O job `dataRequestTimers` (a cada 15 minutos) usa as atualizações condicionais de NFR3.4, então repetir não gera duplicata. As exceções são capturadas no executor |
| NFR5.1 | A migração `0014-data-requests.sql` cria as três tabelas e os índices, cada changeset com rollback. O `DataRequestsMigrationTest` recusa `UPDATE`, `DELETE`, `DROP` e `ALTER TABLE` de tabela existente fora do rollback |
| NFR4.1 | As telas usam `h1` por página, `h2` por seção e as regiões `main` e o rodapé existente. Os campos têm `label` visível, e o contador de caracteres usa `aria-describedby`. Os alertas de prazo usam ícone com `aria-hidden` mais o texto "Vence em X dias" ou "Vencido". As mensagens ficam em `aria-live="polite"` |
| NFR4.2 | As listas têm os estados `loading` ("Carregando pedidos"), `error` (mensagem com "Tentar de novo") e `empty` ("Nenhum pedido ainda") |
