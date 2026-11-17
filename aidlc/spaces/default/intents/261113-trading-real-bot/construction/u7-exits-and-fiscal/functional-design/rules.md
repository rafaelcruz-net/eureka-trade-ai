# Regras de negócio — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes:
- `functional-design-questions.md` (Q1 a Q9);
- `inception/user-stories/stories.md` (US3.4, US4.3, AC8.1.3);
- `inception/requirements-analysis/requirements.md` (FR4.1, FR8.2, FR13.1, FR14.1);
- `inception/contract-design/contract-summary.md` (R5, M1, M2, M5, M6, M7, X1);
- `project.md` e `team.md`;
- revisão da iteração 1.

## Regras

### BR0 — Portão da gravação real (ajuste da revisão, iteração 1)

- **BR0.1:** este desenho faz suposições sobre a Trigger API v2 da Jupiter que ainda não foram conferidas numa transação real:
  - a carteira do bot como dona (`maker`);
  - a conta de custódia do programa (vault ou PDA) que guarda o token;
  - os discriminadores das instruções;
  - o cancelamento assinado só pela dona;
  - a leitura das ordens por carteira;
  - a cossinatura aceita;
  - a chave de API.
- **BR0.2:** a gravação real é condição para escrever a `TriggerPolicy` e o adaptador real. Ela estende o `infra/solana-fixtures/record.sh` e é rodada uma vez pelo responsável, com rede. Ela registra:
  - uma criação e um cancelamento de condicional;
  - os programas e as contas;
  - as respostas da API.
- **BR0.3:** até a gravação:
  - o TxSigner recusa `TRIGGER_CREATE` e `TRIGGER_CANCEL` (`POLICY_NOT_RECORDED`);
  - o modo `AUTO_EXITS` fica desligado em toda rede (`memetrade.real.exits.enabled=false`), com "Em breve" na tela (BR9);
  - o domínio, a API, os trabalhos e as telas das condicionais rodam só com o adaptador simulado, que não envia transação.
- **BR0.4:** a exportação fiscal (BR7) não depende da Jupiter e é entregue sem esse portão.
- **BR0.5:** se a gravação mostrar algo diferente de BR1 e BR2, o desenho volta para revisão antes do código da política.

### BR1 — Dono e assinatura da condicional (Q1)

- **BR1.1:** a condicional é criada em nome da carteira do bot (`maker`).
  - O token da posição sai da carteira do bot só para a conta de custódia da ordem, que pertence ao programa Jupiter Trigger.
  - Na execução ou no cancelamento, o resultado volta só para a carteira do bot.
- **BR1.2:** a criação é cossinada, como uma ordem a mercado do modo Assinada:
  1. o servidor monta a transação pela Trigger API (X1);
  2. a carteira principal assina no navegador, numa sessão igual à da R3 (U4/U6);
  3. o TxSigner assina pela carteira do bot, sob a `TriggerPolicy` (BR2).

  O navegador não monta nem altera a transação. Se a gravação mostrar que a Jupiter não aceita a cossinatura, a criação passa a ser assinada só pelo TxSigner. Nesse caso, a autorização da pessoa vira um consentimento assinado da condicional, por `signMessage`, como no `RAISE_MODE`, e o desenho volta para revisão (BR0.5).
- **BR1.3:** o cancelamento é assinado só pelo TxSigner. Ele funciona, como o saque, com:
  - a pessoa offline;
  - o kill switch ligado;
  - o REAL desligado;
  - a restauração em curso.
- **BR1.4:** a carteira principal nunca recebe nem guarda a posição nem o resultado da venda.

### BR2 — Política de assinatura das condicionais (Q2)

- **BR2.1:** a `TriggerPolicy` do TxSigner aceita só os programas Jupiter Trigger, System, Token, Token-2022, Associated Token e Compute Budget. Do programa Trigger, aceita só os discriminadores de criação e de cancelamento gravados (BR0.2).
- **BR2.2:** na criação, aceita só quando:
  - a carteira do bot é `maker` e pagadora;
  - a conta de custódia que recebe o token é a derivada pelo programa Trigger para essa ordem e essa `maker`, com a derivação conferida na gravação. Nenhuma outra conta pode receber o token;
  - o mint de entrada é o da posição;
  - a quantidade não passa do saldo do mint na carteira do bot, conferido na rede no momento da assinatura;
  - a saída é SOL (wSOL);
  - o destino da saída gravado na ordem é a carteira do bot ou a conta wSOL associada a ela;
  - a única outra assinatura é a da carteira principal registrada;
  - a saída mínima não fica abaixo do piso autorizado, quando a instrução tem saída mínima na rede (BR2.6).
- **BR2.3:** no cancelamento, aceita só quando o token sai da conta de custódia daquela ordem e volta para a conta associada da carteira do bot, e a carteira do bot é a pagadora.
- **BR2.4:** recusa e grava o motivo em qualquer outro caso:
  - programa ou discriminador fora da lista;
  - conta de custódia que não é a derivada;
  - destino diferente;
  - troca de autoridade;
  - fechamento de conta para terceiros;
  - mint diferente;
  - quantidade acima do saldo;
  - signatário extra;
  - saída mínima abaixo do piso.
- **BR2.5:** repetir o mesmo `signingRequestId` devolve a mesma assinatura. Conteúdo diferente com o mesmo id devolve `REQUEST_MISMATCH`, como na `SwapPolicy`.
- **BR2.6:** proteção de preço em três camadas:
  - **Na API:** antes de pedir a assinatura, a API recusa um stop abaixo de 50% do preço atual ou um alvo acima de 10 vezes o preço atual (`TRIGGER_OUT_OF_BOUNDS`).
  - **No TxSigner, quando há saída mínima na rede:** o pedido traz o piso (quantidade × preço do stop × (1 − derrapagem)), e o TxSigner recusa saída mínima abaixo dele, como na `SwapPolicy`.
  - **Quando os preços ficam só no serviço da Jupiter (se a gravação mostrar isso):** o TxSigner não consegue conferir o preço. O risco que sobra é uma venda a preço ruim. Ele fica limitado à posição daquela ordem, porque o resultado só volta para a carteira do bot, e vai para a decisão do fundador antes de ligar o flag na mainnet (BR9.3).

### BR3 — Tipos, preços e validação (Q3)

- **BR3.1:** cada posição tem no máximo uma condicional não terminal: `STOP`, `TAKE_PROFIT` ou `OCO` (stop e alvo juntos, um cancela o outro).
- **BR3.2:** os preços são em USD, recebidos como `DecimalString` e guardados em decimal exato, sem ponto flutuante.
- **BR3.3:** sem preço atual em USD da posição (`unknown` na U2), a criação é recusada com `PRICE_UNKNOWN`.
- **BR3.4:** a ordem dos preços é conferida antes:
  - no `OCO`, o alvo precisa ser maior que o stop (`TP_NOT_ABOVE_SL`);
  - o stop precisa ficar abaixo do preço atual e o alvo acima (`TRIGGER_ON_WRONG_SIDE`);
  - os limites de BR2.6 também valem (`TRIGGER_OUT_OF_BOUNDS`).
- **BR3.5:** a distância mínima exigida pela Jupiter é a que a própria Jupiter devolve. A recusa dela vira `TRIGGER_REJECTED`, com o motivo.
- **BR3.6:** a derrapagem do stop e do alvo é a máxima do perfil REAL da pessoa (U3, `maxSlippageBps`), nunca o padrão da Jupiter.
- **BR3.7:** a quantidade é a posição inteira do ledger REAL no momento da criação, que precisa estar toda na carteira do bot (sem outra condicional aberta).
- **BR3.8:** a resposta e a tela dizem que o stop não tem execução garantida.

### BR4 — Barreira e venda dupla (Q6)

- **BR4.1:** a criação passa pela barreira da U3 (M5), que confere a liberação efetiva, as pausas (do usuário e globais), o kill switch, o token bloqueado e o modo `AUTO_EXITS`. As recusas usam os códigos da U3.
- **BR4.2:** a condicional não consome o limite de compra nem o teto. A venda executada entra na perda do dia quando o preenchimento é lançado (BR5).
- **BR4.3:** uma venda a mercado da mesma posição (do bot, da pessoa ou da liquidação da U4) cancela antes a condicional não terminal, e a venda só segue depois de `CANCELLED` ou `FILLED`. Antes de cotar, a venda relê a posição no ledger REAL (BR5.3b). Com posição zero, porque a condicional preencheu, a venda termina sem enviar nada (`NOTHING_TO_SELL`). Assim a mesma posição nunca é vendida duas vezes.
- **BR4.4:** criar uma condicional pede `Idempotency-Key`. Repetir a mesma chave com o mesmo corpo devolve a mesma condicional.

### BR5 — Preenchimento (Q4)

- **BR5.1:** a cada 30 s, um trabalho lê na Jupiter o estado das condicionais `OPEN` e `CANCELLING` da carteira do bot.
- **BR5.2:** um preenchimento só vale depois de conferido na rede pela assinatura da transação, com commitment `finalized`. A quantidade vendida, o SOL recebido e a taxa vêm da rede, nunca da Jupiter.
- **BR5.3:** o preenchimento entra no ledger REAL pela M2 com os tipos que já existem (`RealEntryRules`), sem tipo novo nem mudança de CHECK (ajuste da revisão, iteração 2):
  - um único `SWAP_FILL_OUT` (venda), com os lamports recebidos (positivos) e a quantidade de tokens vendida, como a venda a mercado da U4;
  - um `NETWORK_FEE` só quando a carteira do bot pagou taxa na transação de execução, o que é conferido na rede. Quando a execução é paga pela Jupiter, não há lançamento de taxa;
  - a coluna `reference` recebe `conditional:<conditionalOrderId>`.

  As chaves de idempotência são `cond:<fill_signature>:fill` e `cond:<fill_signature>:fee`, então a mesma assinatura nunca gera um segundo lançamento. Os lançamentos e o `FILLED` gravam na mesma transação do banco e atualizam a posição e o P&L REAL.
- **BR5.3b:** a fonte de verdade da posição é o ledger REAL (compras menos vendas), não o saldo da carteira do bot.
  - Enquanto a condicional está `OPEN` ou `CANCELLING`, o token fica na conta de custódia da ordem. A posição continua aberta no ledger, e a tela a mostra como "em saída automática".
  - A reconciliação da U5 soma ao saldo do mint na carteira do bot o saldo da conta de custódia de cada condicional `OPEN` ou `CANCELLING` desse mint, lido na rede pela conta gravada na criação. Assim a ordem aberta não vira divergência.
  - Uma venda a mercado só segue com posição maior que zero no ledger depois do `FILLED` ou do `CANCELLED`. Com posição zero, ela termina com `NOTHING_TO_SELL` (BR4.3).
- **BR5.4:** num `OCO`, preenchida uma perna, a condicional fica `FILLED` com `filled_side`, e a outra perna deixa de existir.
- **BR5.5:** cada mudança de estado é publicada no canal ao vivo (`real.conditional.state`) e auditada.
- **BR5.6:** com a Jupiter fora, a leitura tenta de novo no próximo ciclo. Uma condicional `OPEN` sem leitura bem-sucedida há mais de 2 horas gera alerta.

### BR6 — Cancelamento automático (Q5)

- **BR6.1:** com o kill switch REAL ligado (M7), um trabalho de 1 minuto cancela as condicionais `OPEN` e `PENDING_SIGNATURE`.
- **BR6.2:** ao sair do modo `AUTO_EXITS` (para `SIGNED` ou `AUTO_FULL`), a troca de modo vale na hora, e as condicionais da pessoa são canceladas logo depois.
- **BR6.3:** com a liberação revogada, as condicionais da pessoa são canceladas.
- **BR6.4:** enquanto o cancelamento não confirma, a condicional fica `CANCELLING`.
  - O trabalho tenta de novo a cada minuto: lê a Jupiter e a rede e monta outro cancelamento se o anterior expirou sem confirmar.
  - Com 15 minutos em `CANCELLING`, sai um alerta.
  - A condicional nunca volta para `OPEN` sozinha.
- **BR6.5:** uma condicional em `PENDING_SIGNATURE` é cancelada direto, sem transação, para `CANCELLED`, nos mesmos gatilhos. A sessão de assinatura aberta é encerrada.
- **BR6.6:** se a condicional for preenchida antes de o cancelamento confirmar, vale o preenchimento (BR5).
- **BR6.7:** `needsUserCancellation` (contrato R5) passa a ser verdadeiro só quando o cancelamento automático está parado há mais de 15 minutos (BR6.4). Nesse caso, a tela pede que a pessoa avise o suporte. Isso substitui a regra do contrato original, que pedia o cancelamento à pessoa, porque agora o servidor cancela sozinho (adendo da U7).
- **BR6.8:** o saque continua disponível em todos esses casos. Antes do saque total, a liquidação da U4 cancela as condicionais (BR4.3).
- **BR6.9:** na anonimização (U5), o `closeForAnonymization` já recusa quem tem posição (`POSITIONS`). A U7 acrescenta a recusa com condicional não terminal (`OPEN_CONDITIONALS`, com o texto "Cancele as saídas automáticas ou venda tudo antes"). A anonimização não cancela nada sozinha.

### BR7 — Exportação fiscal (Q7)

- **BR7.1:** CSV UTF-8 com BOM, separador `;`, vírgula decimal e quebra de linha `\r\n`, com uma linha por operação da pessoa no período, montada a partir do ledger REAL.
- **BR7.2:** colunas, nesta ordem: `data_utc`, `data_local`, `tipo`, `mint`, `simbolo`, `quantidade`, `preco_sol`, `valor_sol`, `taxa_sol` e `assinatura`.
- **BR7.3:** mapeamento dos lançamentos para as linhas:

  | Lançamentos (pela mesma referência) | `tipo` | `quantidade` | `valor_sol` | `taxa_sol` |
  |---|---|---|---|---|
  | saída de SOL e entrada de token de uma ordem de compra | `COMPRA` | tokens recebidos | SOL pago | taxa da ordem |
  | saída de token e entrada de SOL de uma ordem de venda | `VENDA` | tokens vendidos | SOL recebido | taxa da ordem |
  | os mesmos, com referência `CONDITIONAL_ORDER` | `VENDA_CONDICIONAL` | tokens vendidos | SOL recebido | taxa |
  | `DEPOSIT` | `DEPOSITO` | vazio | SOL depositado | vazio |
  | `WITHDRAWAL` | `SAQUE` | vazio | SOL sacado | taxa do saque |
  | `NETWORK_FEE` sem ordem nem saque | `TAXA` | vazio | vazio | taxa |
  | `RENT` | `ALUGUEL_CONTA` | vazio | lamports do aluguel em SOL | vazio |
  | `COMPENSATION` | `AJUSTE` | conforme o ativo | conforme o ativo | vazio |
  | `RESERVE`, `RESERVE_RELEASE` | não entram (são reservas internas, não operações) | — | — | — |

  Uma `NETWORK_FEE` ligada a uma ordem ou a um saque aparece só em `taxa_sol` daquela linha, nunca numa linha `TAXA` separada. Assim a taxa não é contada duas vezes.
- **BR7.4:** sinais e valores:
  - `quantidade`, `valor_sol` e `taxa_sol` são sempre positivos; o `tipo` diz a direção;
  - no `AJUSTE`, um sufixo no tipo diz a direção: `AJUSTE_ENTRADA` ou `AJUSTE_SAIDA`;
  - `preco_sol` = `valor_sol` ÷ `quantidade`, com as casas do mint mais 9, sem arredondar além disso; fica vazio sem quantidade;
  - campo sem dado fica vazio, nunca zero.
- **BR7.5:** os números são escritos como números, sem prefixo. A proteção contra fórmula vale só para campos de texto (`simbolo`): um texto que começa com `=`, `+`, `-`, `@`, tabulação ou retorno recebe um apóstrofo antes. Como os números são sempre positivos, nunca começam com `-`.
- **BR7.6:** a soma abaixo, no período, bate com a variação do saldo de SOL do ledger REAL (sem reservas) no mesmo período:

  `DEPOSITO` − `SAQUE` − taxa do saque + `VENDA` + `VENDA_CONDICIONAL` − `COMPRA` − taxas das ordens − `TAXA` + `AJUSTE_ENTRADA` − `AJUSTE_SAIDA` − `ALUGUEL_CONTA`

  Um teste confere isso num ledger de exemplo.
- **BR7.7:** o período vem em `from` e `to`, que são datas no fuso da pessoa, com no máximo 1 ano por arquivo. Sem eles, valem os últimos 12 meses.
- **BR7.8:** cada pessoa só exporta os próprios dados (tenant do token). O PAPER nunca entra.
- **BR7.9:** limite de 10 exportações por pessoa por hora, com o limitador por pessoa que já existe na API (o mesmo dos pedidos de dados do intent `261109-termos-lgpd`). Acima disso, `429 RATE_LIMITED` com `Retry-After`.
- **BR7.10:** o arquivo é gerado sob demanda e não fica guardado no servidor. A exportação é auditada sem o conteúdo.
- **BR7.11:** a exportação continua disponível com o kill switch, sem liberação e com o REAL pausado. Ela segue a regra do novo aceite como as outras leituras REAL.

### BR8 — Telas (Q8)

- **BR8.1:** o modo "Automática nas saídas" fica habilitado quando `memetrade.real.exits.enabled` está ligado (BR9). Desligado, aparece "Em breve", e o servidor recusa subir para `AUTO_EXITS` com `EXITS_DISABLED`. Quem já está em `AUTO_EXITS` continua nele, com as ordens a mercado cossinadas como hoje; só a criação de condicionais fica recusada.
- **BR8.2:** cada posição da visão geral ganha o botão "Definir stop e alvo", que abre um diálogo com:
  - o preço atual em USD, com fonte e horário;
  - os campos de stop e alvo como texto decimal, sem ponto flutuante;
  - o aviso do stop;
  - os limites de BR2.6.

  A confirmação abre a faixa e o diálogo de assinatura da U6.
- **BR8.3:** a tela de ordens ganha a aba "Condicionais", com os estados:
  - "Aguardando assinatura";
  - "Aberta";
  - "Preenchida";
  - "Cancelando";
  - "Cancelada";
  - "Expirada";
  - "Falhou".

  Quando ocorre o caso de BR6.7, aparece o aviso "O cancelamento automático está demorando; o suporte foi avisado. Você continua podendo sacar o SOL livre". O alerta de 15 minutos chega ao administrador, que acompanha a condicional pela área REAL.
- **BR8.4:** a tela de ordens ganha o botão "Exportar histórico (CSV)", com o período. O download sai pela própria rota e não guarda nada no navegador.

### BR9 — Integração com a Jupiter (Q9)

- **BR9.1:** a Jupiter Trigger fica atrás de uma porta (`TriggerPort`, X1), com duas implementações:
  - simulada: rede local e testes, sem transação;
  - real: Trigger API v2, depois do portão BR0.
- **BR9.2:** os testes de unidade usam as respostas gravadas (BR0.2).
- **BR9.3:** o flag `memetrade.real.exits.enabled` começa desligado em toda rede e só muda conforme o caso:
  - antes da gravação (BR0): fica desligado em todas;
  - depois da gravação: pode ser ligado na rede local e na devnet;
  - na mainnet: ligar exige a aprovação do fundador sobre o risco que sobra de BR2.6.

  Com o flag desligado, a criação devolve `403 EXITS_DISABLED`, e as leituras, o cancelamento e a exportação continuam funcionando.
- **BR9.4:** a chave de API da Jupiter, se for exigida, fica num segredo só do worker e nunca vai para o navegador.

## Resumo

- 10 grupos de regras, com o portão da gravação real (BR0).
- Sem tipo novo de lançamento nem mudança de CHECK no ledger.
- As regras do `project.md` continuam valendo:
  - o navegador só assina;
  - o TxSigner só assina dentro da política;
  - não há ponto flutuante em dinheiro nem em preço;
  - REAL e PAPER ficam separados;
  - o saque está sempre disponível;
  - o tenant vem do token.
