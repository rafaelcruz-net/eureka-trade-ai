# Desenho de confiabilidade — U8 Administração e feedback

Base: [requisitos de confiabilidade](../nfr-requirements/reliability-requirements.md), [respostas](nfr-design-questions.md) Q1 e Q2 e regras BR2.3 a BR2.8. Este desenho fecha:
- o R-01 e o R-02 da revisão dos requisitos não funcionais;
- o R-09 e o R-10 da segunda revisão do desenho funcional.

## Kill switch: gravação, trava de aplicação e reconciliação

### Componentes

| Componente | Papel |
|---|---|
| `KillSwitchService` | Grava o estado (BR2.1, BR2.2, BR2.4) na transação da requisição, com `SELECT ... FOR UPDATE` na linha única |
| `KillSwitchApplier` | Única classe que chama `BotAdministration.applySystemPause` e `liftSystemPause`. Sempre sob a trava de aplicação |
| `ApplyLock` | Trava consultiva de sessão do PostgreSQL (`pg_try_advisory_lock(<chave fixa da U8>)`) numa conexão própria, retirada de um pool de 1 conexão dedicado à trava |
| `KillSwitchReconciler` | Trabalho do processo de API a cada 30 s e uma vez ao subir o processo |

### Trava de aplicação (Q1)

1. **Sem prazo de validade.** A trava é de sessão. Ela é solta por `pg_advisory_unlock` no `finally`, ou pelo PostgreSQL quando a conexão cai (processo morto ou rede caída). Ela nunca vence no meio de uma chamada à U5.
2. **Exclusão dentro do processo.** A trava consultiva é reentrante na mesma sessão. Por isso, antes dela, o `ApplyLock` toma um `ReentrantLock` justo da JVM com `tryLock`. O orçamento de espera é único, de 10 s, e é dividido entre:
   - o `ReentrantLock`;
   - a conexão do `DataSource` dedicado da trava: Hikari com `maximumPoolSize = 1`, `minimumIdle = 1` e `connectionTimeout` igual ao tempo que sobra do orçamento (nunca o padrão de 30 s);
   - as tentativas de `pg_try_advisory_lock` a cada 200 ms.
3. **Espera esgotada.** Quem não consegue a trava dentro dos 10 s (R-10 do desenho funcional, NFR2.3) grava `reconcileNeeded = true` e responde 200. O trabalho conclui.
4. **Conexão perdida durante a chamada.** Depois da chamada à U5, o applier confere a conexão da trava (`isValid(1)`). Se ela caiu, a trava pode ter sido tomada por outro processo durante a chamada. Nesse caso, o applier grava `reconcileNeeded = true` pela conexão normal e não grava `pendingBots`. A reconciliação seguinte aplica o estado atual de novo, e a janela de desordem fica limitada a 30 s.
5. **Regras de gravação (fencing).**
   - Marcar `reconcileNeeded = true` nunca usa `version`: é um `UPDATE` incondicional, que qualquer um pode fazer e que não muda `version`.
   - Limpar `reconcileNeeded` e gravar `pendingBots` só são feitos por quem tem a trava, com `UPDATE ... WHERE version = :lida AND reconcile_marked_at <= :inicio_da_aplicacao`. Um pedido de reconciliação feito durante a aplicação nunca é apagado.
   - Depois de 3 repetições com `version` ainda mudando, o applier grava `reconcileNeeded = true` e solta a trava.
3. **Releitura sob a trava.** Com a trava, o `KillSwitchApplier` lê `state` e `version` numa transação curta e chama a operação do estado lido (ON → `applySystemPause`, OFF → `liftSystemPause`), nunca a do estado que a requisição gravou.
4. **Conferência depois da chamada.** Depois da chamada, ele relê `version`.
   - Se `version` mudou, repete os passos 3 e 4, até 3 vezes.
   - Se não mudou, grava `pendingBots` e `reconcileNeeded` só se `version` continua igual (`UPDATE ... WHERE version = :lida`).
5. **Exceção da U5.** Uma exceção da U5 grava `reconcileNeeded = true` e é registrada com o código `KILL_SWITCH_APPLY_FAILED`.

Por que a ordem fica garantida: as chamadas à U5 acontecem uma de cada vez, e cada uma aplica o estado mais novo confirmado. Um `apply` que chegaria atrasado depois de um OFF passa a ser um `lift` (NFR10.2).

### Reconciliação (NFR10.1)

- **Quando roda:** a cada 30 s, sempre, e uma vez no `ApplicationReadyEvent` do processo de API (Q2).
- **O que faz:** pega a trava de aplicação e sempre chama o `KillSwitchApplier` com a operação do estado atual: `applySystemPause` com ON, `liftSystemPause` com OFF. As duas operações da U5 são idempotentes e só mexem nos bots que ainda divergem (C16). Com 60 bots, o custo de uma execução sem mudança é de poucas consultas.
- **Por que sempre:** isso cobre uma queda no meio dos lotes da U5 (R-01 desta revisão). Nesse caso, a U5 já gravou `systemPaused = true`, mas parte dos bots ainda não tem SYSTEM; a próxima reconciliação os pausa, mesmo com `pendingBots = 0` e `reconcileNeeded = false`.
- **Divergência:** a reconciliação conta como divergência `changed > 0` com o estado já estável, ou `systemPaused() != (state = ON)`. Uma divergência incrementa o contador do NFR9.1 e gera o log ERROR do NFR9.3.
- **Tempo máximo:** 45 s até todos os bots ficarem pausados no pior caso (5 s de resposta, mais 10 s de espera, mais 30 s até a próxima execução). No caminho normal, 5 s. Esse tempo substitui os 35 s do NFR2.2 e do NFR10.1 dos requisitos (R-06 desta revisão), por decisão do responsável do projeto na Q2.
- **Testes de queda:** `KillSwitchCrashIT` cobre três quedas:
  - antes da chamada à U5;
  - no meio dos lotes da U5, com o dublê da U5 interrompendo depois de 30 dos 60 bots;
  - com perda da conexão da trava durante a chamada.

  Nos três casos, todos os 60 bots ficam com SYSTEM dentro de 45 s.

### Correção do texto do desenho funcional (R-09)

O desenho funcional ainda mostra o texto anterior. Vale o que segue:
- A máquina de estados do kill switch tem como gatilho "reconciliação" sempre, não "reparo com `pendingBots > 0`".
- F2, F3 e BR2.7 também devolvem e mostram `reconcileNeeded`.
- A tabela de eventos de auditoria fica com as linhas ADMIN_LOGIN, ADMIN_LOGOUT, ADMIN_SESSION_REVOKED, LOGIN_REJECTED, KILL_SWITCH_ON, KILL_SWITCH_OFF e FEEDBACK_PURGED. As recusas por papel usam o REQUEST_FORBIDDEN existente.

A geração de código segue este documento nesses pontos.

## Outras garantias

| ID | Desenho |
|---|---|
| NFR10.3 | O `KillSwitchApplier` grava `pendingBots = failed.size()` depois de até 3 tentativas. A reconciliação repete. O teste injeta falha num subconjunto de bots pelo dublê da U5 |
| NFR10.4 | O `SystemStatusAssembler` chama as seis partes em paralelo (pool próprio de 6 threads), cada uma com `orTimeout(500 ms)`. Exceção ou tempo esgotado viram `{status: UNKNOWN, reason: TIMEOUT \| UNAVAILABLE}`. Nenhum valor padrão numérico |
| NFR10.5 | O diálogo guarda o texto no estado do componente até receber 201. A Idempotency-Key é gerada ao abrir e reaproveitada em reenvios |
| NFR10.6 | Sem meta de disponibilidade. O serviço `admin` tem healthcheck no Compose |
| NFR10.7 | A migração `0011-admin-feedback.sql` só cria `kill_switch_state` (com a linha OFF e as colunas `pending_bots`, `reconcile_needed` e `reconcile_marked_at` para o fencing), `kill_switch_change` e `feedback_entry`. Ela amplia a restrição de `user_session.role` para aceitar `ADMIN`, sem remover nada |
| Idempotência do kill switch (R-03) | O `PUT /api/v1/admin/kill-switch` exige `Idempotency-Key` pelo mecanismo comum da identidade. Teste com a mesma chave e o mesmo corpo (mesma resposta, sem novo histórico) e com corpo diferente (409) |
