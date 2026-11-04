# Desenho de escalabilidade — U3 Convites, contas e sessões

Como a Unit `u3-identity-access` atende a [scalability-requirements.md](../nfr-requirements/scalability-requirements.md) (NFR2.1 a NFR2.4 da U3). O beta tem 20 convidados e 1 administrador; nada aqui escala horizontalmente ainda.

| Decisão | Detalhe | Requisito |
|---|---|---|
| Volume pequeno sem estrutura especial | Até 200 convites e 20 contas; tabelas comuns com índices, sem particionamento nem cache | NFR2.1 |
| Paginação por cursor | `limit` padrão 20, máximo 100; cursor opaco com `createdAt` e `inviteId`; `limit` fora do intervalo responde `400 VALIDATION_FAILED` | NFR2.2 |
| Contadores do limite em memória | `InviteLookupRateLimiter` é um limitador próprio, com janela de 1 minuto e chave pelo IP, separado do `LoginRateLimiter`. O `LoginLimiterEvictionJob` que já existe passa a receber os dois limitadores e a chamar `evictExpired()` de ambos no mesmo agendamento; as chaves sem requisição dentro da janela de 1 minuto são removidas, então chaves de IP criadas por um atacante não se acumulam. O job continua com a mesma entrada no registro de rotas e trabalhos (`LoginLimiterEvictionJob`, `GLOBAL`). O limite vem de `memetrade.auth.invite-lookup-rate-limit-per-minute` (10; elevado só no perfil `loadtest`). Com mais de uma instância, o limite passa a valer por instância; a troca para um contador compartilhado fica para a hospedagem | NFR2.3 |
| Contagens agregadas | `IdentityAdministration.counts()` faz uma consulta agregada por estado derivado dos convites (com o instante como parâmetro) e outra por papel e status das pessoas | NFR2.4 |
| API sem estado entre requisições | Fora os contadores do limite, nenhum estado da U3 fica na memória do processo além dos termos lidos na subida | NFR2.3 |
