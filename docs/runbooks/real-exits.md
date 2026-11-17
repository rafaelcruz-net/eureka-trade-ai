# Runbook — saídas automáticas REAL (stop e alvo) e exportação fiscal

Intent `261113-trading-real-bot`, Unit U7 (`u7-exits-and-fiscal`). Fontes: `functional-design/rules.md` (BR0 a BR9), `nfr-design/` (reliability, observability e logical components) e `infrastructure-design/infrastructure-specification.md`.

Regra de ouro: **o saque para a carteira principal registrada continua disponível em todos os casos deste runbook** — com o flag desligado, com o kill switch, com condicional em `CANCELLING` e durante o rollback. Só o token que está na custódia da Jupiter (condicional `OPEN` ou `CANCELLING`) não é SOL livre até o cancelamento ou o preenchimento confirmar.

## 1. Portão da gravação real (BR0)

Até a gravação real da Trigger API v2 da Jupiter, valem estas travas:

| Trava | Onde fica | Como conferir |
|---|---|---|
| O TxSigner recusa `TRIGGER_CREATE` e `TRIGGER_CANCEL` com `POLICY_NOT_RECORDED` e não registra nada | `SigningService` do TxSigner; o `trigger-program.json` não existe no classpath | métrica `txsigner_trigger_refusals_total{reason="POLICY_NOT_RECORDED"}`; testes `SigningServiceTriggerTest` e `TriggerSignaturesIT` |
| O flag `memetrade.real.exits.enabled` fica `false` em toda rede | `application.yml` (`MEMETRADE_REAL_EXITS_ENABLED`, padrão `false`) | `GET /api/v1/real/release` devolve `exitsEnabled: false`; a criação devolve `403 EXITS_DISABLED`; a tela mostra "Em breve" |
| O adaptador simulado só existe no perfil `test` e só na `LOCALNET` | `SimulatedTriggerAdapter` (`@Profile("test")`), `RealProperties` recusa `simulated` fora da `LOCALNET` | `RealExitsSettingsTest`, regra ArchUnit `RealExitsArchitectureTest` |
| Não existe adaptador real | `realexits/infra/jupiter/` vazio de propósito | revisão de código; sem o adaptador real, o contexto usa o `UnavailableTriggerPort`, que responde "indisponível" |

**Gravar (feito uma vez pelo responsável, com rede, sem chave e sem enviar nada):**

```
bash infra/solana-fixtures/record.sh --trigger --mint <mint com saldo> --maker <carteira pública com saldo do mint> [--rpc <url de RPC da mainnet>]
```

- O script só pede à Trigger API as transações de criação e de cancelamento **sem assinatura** para uma carteira pública escolhida pelo responsável. Ele não assina e não envia nada.
- Ele grava em `infra/solana-fixtures/trigger/` as respostas, os vetores, o `trigger-program.json` e o `.sha256`, e clona o programa Trigger e as contas para o validador local.
- Se a Jupiter exigir chave de API ou recusar montar a transação para uma carteira sem chave (resposta 401/403), o script para com código 3. A decisão volta para o responsável antes de qualquer gasto: fundos reais só com aprovação explícita (`project.md`).
- Se a gravação mostrar algo diferente de BR1 e BR2 (dona, custódia, discriminadores, cossinatura), o desenho volta para revisão antes do código da política (BR0.5).

Depois da gravação, numa entrega própria e fora desta Unit: a `TriggerPolicy` completa, com os testes de ataque escritos antes do código, e o `JupiterTriggerAdapter` real. O `trigger-program.json` só é aceito pelo TxSigner com o hash conferido (`TXSIGNER_TRIGGER_PROGRAM_SHA256`); um hash diferente falha a subida.

## 2. O flag das saídas (BR9.3)

| Momento | Rede local | Devnet | Mainnet |
|---|---|---|---|
| Antes da gravação | só o perfil de teste local, com o adaptador simulado (`infra/docker-compose.real.yml`) | desligado | desligado |
| Depois da gravação e da `TriggerPolicy` | pode ligar | pode ligar | só com a aprovação do fundador sobre o risco que sobra de BR2.6 (seção 5) |

Variáveis (API e worker, as duas iguais):

- `MEMETRADE_REAL_EXITS_ENABLED` (`false` por padrão);
- `MEMETRADE_REAL_EXITS_ADAPTER` (`jupiter` por padrão; `simulated` só no perfil `test` e na `LOCALNET`);
- `MEMETRADE_JUPITER_API_KEY` (só se a Jupiter exigir; segredo só do worker, nunca no navegador nem no log).

Com o flag desligado, só a **criação** é bloqueada (`403 EXITS_DISABLED`) e a subida para o modo `AUTO_EXITS` é recusada. As leituras, o cancelamento (pela pessoa e pelos gatilhos), a leitura dos preenchimentos e a exportação fiscal continuam funcionando. Quem já está em `AUTO_EXITS` continua nele.

## 3. Cancelamento parado (BR6.4, BR6.7)

**Sinal:** alerta `RealExitsCancellingStalled` (`real_conditional_cancelling_oldest_seconds > 900` por 1 min). A pessoa vê em "Condicionais": "O cancelamento automático está demorando; o suporte foi avisado. Você continua podendo sacar o SOL livre".

**Passos:**

1. Confirmar que o worker está vivo: `RealExitsJobStalled{job="cancel"}` não pode estar disparado. Se estiver, o problema é o trabalho (seção 4), não a Jupiter.
2. Conferir a Jupiter e o RPC (os mesmos painéis da U4). Com a Jupiter fora, o trabalho tenta de novo a cada minuto e monta outro cancelamento se o anterior expirou sem confirmar.
3. Acompanhar a condicional pela área REAL do administrador. Ela nunca volta para `OPEN` sozinha. Se for preenchida antes de o cancelamento confirmar, vale o preenchimento (BR6.6) e o ledger recebe a venda.
4. Não editar `real_conditional_order` à mão: as transições passam pelo gatilho de guarda e o ledger é só de inclusão.
5. Se passar de 2 horas sem leitura bem-sucedida de uma condicional `OPEN`, dispara `RealExitsConditionalUnread`; tratar como incidente da Jupiter.

O saque do SOL livre continua disponível o tempo todo. O token da custódia só volta depois do cancelamento confirmado.

## 4. Trabalhos parados e recusas da política

| Alerta | Significado | Ação |
|---|---|---|
| `RealExitsJobStalled{job="read"}` (2 min) | a leitura das condicionais parou no worker | reiniciar o worker; conferir o ShedLock `realExitsRead` |
| `RealExitsJobStalled{job="cancel"}` (3 min) | o cancelamento automático parou no worker | reiniciar o worker; conferir o ShedLock `realExitsCancel` |
| `RealExitsJobStalled{job="exits-gauges"}` (2 min) | as medidas das condicionais pararam | reiniciar o worker; os alertas acima ficam cegos até lá |
| `RealExitsJobStalled{job="purge"}` (26 h) | o expurgo diário (05:30 UTC, processo da API) não rodou | conferir a API e o lock `real-exits-purge`; o expurgo só remove condicionais finais com mais de 5 anos |
| `RealExitsTriggerPolicyRefused` | o TxSigner recusou um pedido de condicional | antes da gravação é esperado zero pedidos: qualquer recusa indica o flag ligado sem a política; desligar o flag (seção 2) |

A paridade entre as regras do Prometheus (`infra/observability/real-exits-alerts.yml`) e os alarmes do CloudWatch (`infra/terraform/modules/observability/real-exits.tf`, `Eureka/RealExits`) é conferida pelo `RealExitsAlarmParityTest`. Na nuvem, os alarmes só disparam quando o caminho comum de métricas do worker e do TxSigner existir (pendência comum de U1 a U7).

## 5. Risco que sobra de BR2.6 (decisão do fundador)

A proteção de preço tem três camadas:

1. **Na API:** um stop abaixo de 50% do preço atual ou um alvo acima de 10 vezes é recusado (`TRIGGER_OUT_OF_BOUNDS`) antes de pedir a assinatura.
2. **No TxSigner:** quando a instrução tem saída mínima na rede, o pedido traz o piso (quantidade × preço do stop × (1 − derrapagem)) e o TxSigner recusa saída mínima abaixo dele.
3. **Quando os preços ficam só no serviço da Jupiter:** o TxSigner não consegue conferir o preço.

**Risco que sobra (camada 3):** uma venda a preço ruim. Ele fica limitado à posição daquela ordem, porque o resultado só volta para a carteira do bot. O stop também não tem execução garantida num movimento rápido; a tela diz isso antes da confirmação.

**Decisão pendente:** a gravação (seção 1) mostra em qual caso a Jupiter fica. Antes de ligar o flag na mainnet, o fundador decide se aceita esse risco. A decisão é registrada no intent.

## 6. Implantação e rollback

**Ordem de implantação:** migração `0022-real-exits.sql`; worker e API; TxSigner (não muda sem a gravação); web e admin. O smoke roda depois de cada etapa. O flag continua desligado fora do perfil de teste local.

**Rollback.** Uma condicional `OPEN` ou `CANCELLING` tem o token na custódia da Jupiter, e as imagens antigas não sabem ler, cancelar nem reconciliar essa ordem. Por isso:

1. Desligar o flag `memetrade.real.exits.enabled` (API e worker).
2. Ligar o kill switch REAL pelo administrador. O `ConditionalCancelJob` cancela todas as condicionais não terminais (BR6.1), mesmo com o flag desligado.
3. Esperar `real_conditionals{state=~"OPEN|CANCELLING|PENDING_SIGNATURE"}` chegar a zero. O limite é de 5 min com a rede saudável.
4. Só então voltar as imagens, na ordem inversa: web e admin; TxSigner; worker e API.
5. A migração 0022 segue a regra da tabela vazia: o rollback dela só roda com `real_conditional_order` vazia. As condicionais finais ficam; não desfazer a migração com linhas.
6. Com as imagens antigas no ar e o smoke verde, desligar o kill switch REAL.

**Parada:** se alguma condicional continuar não terminal depois de 15 min, o rollback para no passo 3. O alerta `RealExitsCancellingStalled` já está ativo; seguir a seção 3. O saque continua disponível durante todo o rollback.

**Pools.** A U7 sobe o mínimo do agendador da API para 23 e, no worker, o agendador para 28 e o pool de conexões para 36 (`RuntimeSettingsGuard`). Ao voltar as imagens antigas, os valores maiores continuam aceitos.

## 7. Exportação fiscal (BR7)

- Rota: `GET /api/v1/real/exports/fiscal?from=<yyyy-mm-dd>&to=<yyyy-mm-dd>&tz=<zona>`; sem datas, os 365 dias até hoje; período máximo de 1 ano (366 dias são recusados com `422`).
- O arquivo é gerado sob demanda a partir do ledger REAL, em fluxo, e não é guardado no servidor nem no navegador. O nome é `historico-real-<de>-<até>.csv`.
- Formato: UTF-8 com BOM, separador `;`, vírgula decimal, fim de linha CRLF; `data_utc` em UTC e `data_local` na zona pedida (padrão `America/Sao_Paulo`).
- Limite: 10 exportações por pessoa por hora (`429` com `Retry-After`). Cada exportação gera a auditoria `REAL_FISCAL_EXPORTED` e a métrica `real_fiscal_exports_total{result}`.
- A soma das linhas é a variação do SOL do ledger REAL no período (BR7.6). Uma diferença indica lançamento fora do mapeamento: abrir defeito com o período e o `correlationId`, nunca corrigir o arquivo à mão.

## 8. Limite do ambiente local (lacuna aceita)

Até a gravação real, o **preenchimento** de uma condicional (BR5) só tem prova em testes de integração (`FillPostingIT`, `ConditionalCancelJobIT`) com respostas e transações simuladas, sem teste de ponta a ponta. O spec `e2e/real-v2-exits` cobre criar pela tela, cancelar pelo `DELETE` e cancelar pelo kill switch, com o adaptador simulado; ele é sempre o último: o Playwright roda os arquivos na ordem alfabética do caminho, por isso a pasta se chama `real-v2-exits`. Com a gravação, o spec passa a usar a transação real.
