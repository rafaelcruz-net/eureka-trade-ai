# Projeto de observabilidade — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `construction/u4-real-execution/nfr-requirements/observability-requirements.md` (U4-NFR5.1 a U4-NFR5.14), o canal ao vivo atual (`DashboardMarkPoller`, `DashboardEvents`, `BotChangeMarks`) e o padrão da U3.

| Requisito | Como o projeto atende |
|---|---|
| U4-NFR5.1 a U4-NFR5.8 | `RealExecutionMetrics` com Micrometer. Os rótulos vêm dos enums fechados, e os contadores são registrados com zero na subida. Os medidores de ambíguas e da `SENT` mais antiga são atualizados só no worker, por tarefa própria a cada 15 s. O TxSigner registra `txsigner_swap_decisions_total`. |
| U4-NFR5.9 a U4-NFR5.14 | Arquivo `infra/observability/real-execution-alerts.yml`, no formato da U3, conferido pelo `promtool`. Alarmes no Terraform (`Eureka/RealExecution`), com teste de paridade, sem o p95. |

## Canal ao vivo (corrige Q5)

O canal ao vivo atual é o `DashboardMarkPoller`. Ele roda só na API e lê, a cada 2 s, as marcas gravadas no banco pelos módulos. Um evento publicado depois do commit no worker não chegaria a ele. Por isso:

- A auditoria e uma **marca do canal** são gravadas na mesma transação da mudança de estado, no worker ou na API, como os bots já fazem com `BotChangeMarks`. Uma mudança desfeita leva a marca junto, e assim nunca é avisada.
- O `DashboardMarkPoller` passa a ler as marcas REAL e a emitir:
  - `RealOrderStateChanged`, com o id da ordem e o estado;
  - `RealSigningSessionOpened`, com o id da ordem e o prazo.

  Os dois são só para o tenant e o usuário donos. O `DashboardEvents` e o OpenAPI do canal mudam junto.
- **Latência:** o aviso chega ao navegador em até 2 s. Na sessão de assinatura, essa demora sai da janela de 8 s, e o usuário fica com cerca de 6 s. Para não depender só do canal, a tela da U6 também consulta `GET /api/v1/real/orders?state=QUOTED` a cada 1 s enquanto houver bot REAL em modo Assinada, como o contrato já prevê para o diálogo aberto. A janela continua dentro dos 10 s do AC3.2.2.
