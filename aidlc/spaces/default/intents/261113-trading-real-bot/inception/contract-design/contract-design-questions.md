# Perguntas de Contract Design — Trading REAL

Conversa: português (Brasil). Base: `inception/units-generation/unit-of-work.md`, `inception/units-generation/unit-of-work-dependency.md`, `inception/domain-design/components.md`, `inception/requirements-analysis/requirements.md` e os contratos do intent anterior (`261026-memetrade-saas-mvp/inception/contract-design/contract-summary.md`: regras comuns da API, C9 auditoria por tabela de saída, C10 a C20 interfaces de módulo).

## Q1. [API externa] Existe algum contrato para consumidor fora do sistema?

A. Não. A API continua privada (só os dois apps). O único contrato novo entre processos é o do TxSigner, interno, numa rede privada
B. Sim, uma API pública para parceiros
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Mecanismo] Como a BotWallet fala com o TxSigner (processo separado)?

A. HTTP interno com OpenAPI própria (`/internal/v1/...`), autenticado por credencial de serviço própria (token de serviço trocado só entre os dois processos, mTLS na nuvem), só na rede privada; o TxSigner nunca é exposto ao navegador
B. gRPC
C. Fila de mensagens
X. Other (please specify)

[Answer]: A

## Q3. [REST] Onde ficam as rotas REAL dos apps?

A. Nas especificações existentes, geradas do código: convidado em `/api/v1/real/...` e administração em `/api/v1/admin/real/...`. Nenhuma requisição declara `mode`; o filtro de modo continua recusando `mode` diferente de `PAPER` nas rotas PAPER, e as rotas REAL só respondem com o REAL ligado no ambiente e a liberação vigente do usuário (senão `403 REAL_NOT_RELEASED`)
B. Uma terceira especificação só para o REAL
C. Campo `mode` nas rotas existentes
X. Other (please specify)

[Answer]: A

## Q4. [Unidades] Como trafegam SOL e quantidades de token?

A. SOL em lamports como texto inteiro (`LamportsString`), quantidade de token em unidades base como texto inteiro com o `decimals` do mint ao lado; a tela converte para exibição. Preços e P&L seguem as escalas existentes (`DecimalString`)
B. SOL como `DecimalString` com 9 casas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Modo Assinada] Como é o contrato da assinatura no navegador?

A. O servidor devolve a transação não assinada (base64), o resumo da mensagem, o prazo (8 s) e os valores para exibir; o navegador pede a assinatura na carteira e devolve só a transação assinada; o servidor confere que a mensagem é idêntica à que montou e que a assinatura é da carteira principal, e só então envia. O navegador nunca envia para a rede
B. O navegador assina e envia direto para a rede
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Consentimento] Como é a mensagem de consentimento assinada?

A. O servidor emite um desafio (`POST /api/v1/real/consents/challenge`) com tipo (ativação, subir modo, subir teto), nonce, validade de 5 min e os valores autorizados; a mensagem é um texto canônico com domínio, finalidade, carteira do bot, teto, modo, nonce e validade, diferente da mensagem de login; o servidor confere e repassa ao TxSigner a mensagem e a assinatura, e o TxSigner confere de novo
B. Reaproveitar a mensagem de login
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Entre módulos] Como os módulos do monólito conversam?

A. Como no intent anterior: interfaces públicas Java com records imutáveis (contratos de esquema compartilhado), auditoria pela tabela de saída C9 com os campos REAL tipados, e a fila RealDecisionRequest como esquema da RealExecution gravado pelo ciclo do bot pela interface com propagação obrigatória. Nenhum provedor chama de volta o consumidor: a U7 lê o estado do kill switch numa interface da U5 a cada passada do monitor de condicionais
B. Eventos assíncronos entre todos os módulos
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Atualização na tela] Como a tela acompanha uma ordem REAL e o depósito?

A. O canal ao vivo existente do painel (fetch em leitura contínua) ganha tipos de evento REAL (estado de ordem, depósito detectado, saque, pausa); a tela de assinatura também consulta `GET` da ordem a cada 1 s enquanto o diálogo está aberto, como reserva
B. Só consulta periódica
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Falhas] Tempo limite e repetição nas fronteiras novas

A. TxSigner: 2 s, repete uma vez com o mesmo `signingRequestId` (assinatura idempotente por pedido); Jupiter: cotação e swap 2 s, sem repetição da cotação vencida; RPC: 3 s por provedor com troca para o segundo; CoinGecko: 5 s. Falha de RPC, Jupiter ou TxSigner devolve `503` e pausa novas ordens sem afetar o saque; erros de domínio são `4xx` auditados (`REAL_CAP_EXCEEDED`, `REAL_PAUSED`, `WITHDRAWAL_DESTINATION_INVALID`, `SIGNING_POLICY_REFUSED` etc.)
B. Sem repetição em nenhuma fronteira
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Versionamento] Política de versão e quebra

A. Como hoje: o OpenAPI nasce do código e a CI compara com o versionado; mudança aditiva é livre (consumidores ignoram campo desconhecido); quebra só com nova versão de rota. A API interna do TxSigner tem versão no caminho (`/internal/v1`) e o TxSigner é implantado antes da API em toda mudança
B. Sem versionamento
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Fornecedores] Quem é dono das portas externas?

A. Cada Unit consumidora: CoinGecko na U2 (porta de mercado existente), Jupiter na U4 (cotação, swap e condicionais), RPC Solana na U1 (ChainWatcher, usada também pela U4), KMS na U1 (só o TxSigner). Cada porta tem implementação simulada para o local e testes com respostas gravadas
B. Um módulo único de integrações
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- API privada; o único contrato novo entre processos é o HTTP interno do TxSigner (`/internal/v1`), com credencial de serviço e mTLS na nuvem [Q1, Q2].
- Rotas REAL em `/api/v1/real/...` e `/api/v1/admin/real/...`, sem `mode` na requisição, com `403 REAL_NOT_RELEASED` sem liberação [Q3].
- SOL em lamports e tokens em unidades base, como texto inteiro [Q4].
- Modo Assinada: o servidor monta, o navegador só assina, o servidor confere a mensagem e envia [Q5].
- Consentimento por desafio próprio, nonce e validade de 5 min, conferido também pelo TxSigner [Q6].
- Interfaces Java entre módulos, auditoria pela tabela de saída, fila de decisões; a U7 lê o kill switch na U5 [Q7].
- Canal ao vivo com eventos REAL e consulta de reserva a cada 1 s [Q8].
- Tempos limite TxSigner 2 s, Jupiter 2 s, RPC 3 s, CoinGecko 5 s; falha pausa novas ordens sem afetar o saque [Q9].
- OpenAPI gerado do código e conferido na CI; TxSigner versionado e implantado antes da API [Q10].
- Portas externas com a Unit consumidora, cada uma com versão simulada [Q11].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
