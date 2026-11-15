# Perguntas de NFR Requirements — U4 `u4-real-execution`

Conversa: português (Brasil). Base:
- `construction/u4-real-execution/functional-design/`: `rules.md` BR1.1 a BR10.2, `functional-spec.md` F1 a F10 e S1 a S30;
- `inception/requirements-analysis/requirements.md`: NFR1 a NFR10;
- `inception/contract-design/contract-summary.md`: X1 Jupiter 2 s, X3 RPC 3 s por provedor com troca, R1 dentro de 2 s;
- `project.md`: sem SDK Solana na produção, TxSigner só dentro da política;
- U1 (TxSigner, `RpcChainWatcher` com dois provedores) e U3: a barreira e a pendência do pool de agendamento do worker.

## Q1. [Desempenho] Quais metas?

A. As metas são p95 e informativas, sem portão na CI:
- **Da decisão do bot ao envio na Automática total:** até 3 s (NFR3), contando consumo da fila, cotação, assinatura e envio.
- **Consumo da fila:** a decisão começa a ser processada em até 500 ms.
- **Tempos limite das chamadas:** Jupiter 2 s, TxSigner 2 s, RPC 3 s por provedor, com troca para o segundo.
- **Rotas de leitura de ordens e P&L:** até 300 ms.
- **Acompanhamento da confirmação:** até 60 s.
B. Sem metas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Segurança] Que proteções a execução exige?

A. As proteções:
- Nenhum SDK Solana na produção: a mensagem v0 é montada e lida por código próprio (Base58 e serialização), e a assinatura da carteira principal é conferida com o Ed25519 nativo do Java.
- O TxSigner aplica a política de swap (BR4.3), com testes de ataque escritos antes do código.
- A rede só é usada nos pacotes adaptadores (`infra.jupiter` e o adaptador RPC da U1), com regra ArchUnit.
- Tenant e usuário vêm da autenticação ou do bot.
- Nenhuma chave, transação assinada inteira ou assinatura aparece em log. A assinatura só aparece mascarada e nos campos tipados da auditoria.
B. Usar o SDK Solana na produção
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Confiabilidade] Como sobreviver a quedas?

A. As regras:
- Todo estado da ordem fica no banco, e as transições são compare-and-set.
- Os trabalhos (consumidor da fila, cotação e assinatura, envio e confirmação, ambíguas, prazo de assinatura, saúde dos fornecedores e liquidação) retomam do estado gravado depois de reinício, com ShedLock ou `SKIP LOCKED`, e nunca reassinam.
- Testes de queda em cada transição provam:
  - uma transação por ordem;
  - nenhuma reserva presa sem ordem viva;
  - nenhuma ordem `CANCELLED` com a transação na rede.
- O saque não depende de nenhum desses trabalhos.
B. Recuperação manual
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Testes] Como provar a execução?

A. Testes antes do código para a máquina de estados, a idempotência e a política de swap do TxSigner (`team.md`):
- **Unidade:** os cenários S1 a S30 com relógio injetável e respostas gravadas da Jupiter e do RPC.
- **Integração:** PostgreSQL real, com concorrência e queda.
- **Ponta a ponta:** o swap no validador Solana local com as contas da Jupiter clonadas, num spec novo que o responsável do projeto acrescenta ao `verify.sh`. Nenhum teste usa devnet, mainnet nem chave real.
- **Cobertura:** piso de 80% na API e no TxSigner.
B. Testes depois do código
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Escala] Para que volume?

A. Para o piloto: 21 usuários, até 5 bots REAL por usuário, uma ordem por bot em andamento, no máximo cerca de 4 decisões por segundo no pico. O pool de agendamento do worker passa de 4 para 12 tarefas, porque U3 e U4 acrescentam trabalhos. As ordens e os envios ficam guardados por 5 anos, pela obrigação fiscal das operações. As decisões descartadas e processadas ficam 13 meses.
B. Dimensionar para 1.000 usuários
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Observabilidade] Que métricas e alertas?

A. As métricas, sem mint, endereço nem usuário nos rótulos:
- ordens por estado e motivo;
- tempo da decisão ao envio;
- tempo até a confirmação;
- ordens ambíguas abertas;
- assinaturas do TxSigner por resultado;
- recusas da política por motivo;
- falhas por fornecedor;
- pausas `UPSTREAM`;
- batimento de cada trabalho.

Os alertas:
- ordem ambígua aberta há mais de 5 min (crítico);
- recusa da política do TxSigner (crítico, porque pode ser ataque ou erro de montagem);
- falha de assinatura (crítico);
- pausa `UPSTREAM` (alto);
- p95 da decisão ao envio acima de 3 s por 10 min (aviso);
- trabalho parado (crítico).

O padrão é o da U3: regras do `promtool` e alarmes no Terraform, com o p95 adiado até a decisão sobre o CloudWatch.
B. Só os logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Tecnologia] Onde e com que ferramentas?

A. A estrutura:
- **Na API:** um módulo novo `realexecution` (`api`, `domain`, `infra`, `infra.jupiter`, `web`); o P&L REAL no módulo `pnl`; o campo `market` no módulo `bots`.
- **No TxSigner:** a política de swap em `apps/txsigner`, ao lado da política de saque.
- **Jupiter:** pelo `RestClient` do Spring, com Resilience4j.
- **Testes:** WireMock para a Jupiter e o validador local da U1 para o swap.

Nenhuma biblioteca nova de produção.
B. Uma biblioteca de terceiros para a Solana
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Rede] Qual rede em cada ambiente?

A. A rede segue a regra da U1 (FR10.4):
- **Testes e `verify.sh`:** validador local.
- **Devnet:** a cotação e o swap da Jupiter não existem lá, então o REAL na devnet testa só carteira, depósito e saque; os swaps ficam provados no validador local com contas clonadas.
- **Mainnet:** só com configuração explícita do ambiente de produção.

Uma configuração com mainnet fora do perfil de produção recusa a subida.
B. Swaps na devnet
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Decisão ao envio em até 3 s; fila em 500 ms; tempos limite de 2 s (Jupiter e TxSigner) e de 3 s no RPC com troca; leituras em 300 ms [Q1].
- Sem SDK Solana: mensagem e Base58 próprios, Ed25519 nativo; política de swap com testes de ataque; rede só nos adaptadores; nada sensível em log [Q2].
- Estado no banco com compare-and-set; trabalhos que retomam sem reassinar; testes de queda em cada transição [Q3].
- Testes antes do código; validador local com contas da Jupiter clonadas; piso de 80% na API e no TxSigner [Q4].
- Escala do piloto; pool do worker de 4 para 12; ordens guardadas por 5 anos [Q5].
- Métricas e alertas no padrão da U3, com ambígua, política e assinatura críticas [Q6].
- Módulo `realexecution`, P&L no `pnl`, política no TxSigner, sem biblioteca nova [Q7].
- Swaps só no validador local até a mainnet; mainnet só com configuração explícita [Q8].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
