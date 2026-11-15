# Decisões de arquitetura — Trading REAL

Conversa: português (Brasil). Fontes: `components.md`, `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md`, o mapa do código (`architecture.md`, `component-inventory.md`, `dependencies.md`) e `domain-design-questions.md` (Q1 a Q7). Revisado depois da segunda leitura, a pedido do fundador.

## ADR-001: Ledger, P&L e barreira com modo REAL nos módulos existentes

- **Context:** 13 tabelas já têm a coluna `mode` com `CHECK (mode = 'PAPER')`, e a chave única da ordem inclui o modo. A regra do projeto exige REAL e PAPER estritamente separados.
- **Decision:** reaproveitar `ledger`, `pnl` e `riskcontrol` com o modo REAL. Uma migração relaxa o `CHECK`; toda consulta filtra pelo modo.
- **Consequences:** (+) regras de ledger append-only, compensação e P&L reaproveitadas; (-) misturar modos vira risco de consulta, coberto por testes obrigatórios de separação.
- **Alternatives Rejected:** módulos REAL separados (duplicaria regras e testes).

## ADR-002: Quatro componentes novos

- **Context:** o REAL precisa de assinatura com chave guardada, execução assíncrona e acesso à rede.
- **Decision:** TxSigner (assinatura isolada), BotWallet (carteira, depósitos, saques, reconciliação, preferências, liberação), RealExecution (fila de decisões, ordens, condicionais), ChainWatcher (porta para a rede, sem regra de negócio).
- **Consequences:** (+) quem assina fica isolado; a rede é trocável; (-) mais interfaces e um processo a mais para operar.
- **Alternatives Rejected:** um único módulo "real".

## ADR-003: Decisão REAL por fila própria, sem outbox genérica

- **Context:** o ciclo do bot roda numa transação curta (trava de 2 s, limite de 5 s). O código só tem a outbox da auditoria (`audit_outbox`); não há outbox de domínio.
- **Decision:** a RealExecution expõe uma interface com propagação obrigatória; o ciclo grava a RealDecisionRequest na mesma transação. Um consumidor da RealExecution pega as decisões com trava (`FOR UPDATE SKIP LOCKED`), em intervalos curtos, e processa fora do ciclo; uma decisão pega e não concluída volta para a fila depois de um prazo; a idempotência pelo mecanismo comum do projeto evita ordem dupla.
- **Consequences:** (+) nenhuma decisão se perde; sem nova infraestrutura; (-) o intervalo do consumidor entra na meta de 3 s (o consumidor roda a cada 500 ms).
- **Alternatives Rejected:** chamada direta do ciclo (estoura a transação curta); outbox genérica nova para um único consumidor.

## ADR-004: Política de conteúdo da assinatura

- **Context:** a assinatura move dinheiro real; uma transação de swap da Jupiter traz várias instruções (embrulhar SOL, criar contas de token, rota por vários programas).
- **Decision:** o TxSigner só assina depois de conferir:
  1. programas só da lista: agregador da Jupiter, System Program, Token Program, Token-2022, Associated Token Account, Compute Budget;
  2. a carteira do bot é o único assinante e o pagador da taxa;
  3. toda transferência de SOL ou de token fora da instrução da Jupiter sai só para contas da própria carteira do bot, ou para a carteira principal registrada na política própria do TxSigner (só em saque); o que acontece dentro da rota da Jupiter, que o TxSigner não decodifica, fica limitado pela quantidade de entrada, pela conta de saída da carteira do bot e pela saída mínima;
  4. a conta de token de saída pertence à carteira do bot;
  5. a saída mínima é pelo menos a cotação menos a derrapagem do usuário;
  6. tabelas de endereços (ALT) são resolvidas e todas as contas conferidas;
  7. nenhuma instrução de troca de autoridade nem de fechamento de conta para terceiros;
  8. o SOL total que sai não passa do valor da ordem mais as taxas, e o valor de compra não passa do teto autorizado na política própria do TxSigner.

  Os limites que importam (carteira principal de saque, teto e modo autorizados) vêm da WalletPolicy do próprio TxSigner, derivada só de consentimentos assinados pela carteira principal, que ele confere (nonce, validade, valores autorizados). O contexto enviado pela API só pode restringir.
  Qualquer falha recusa e registra o motivo.
- **Consequences:** (+) um bug ou invasão na API não consegue desviar fundos; (-) decodificar transações Solana sem SDK é código próprio, com testes a partir de transações reais gravadas.
- **Alternatives Rejected:** conferir só programa e valor (rasa demais).

## ADR-005: Modelo de ameaças da carteira do bot (G2 do PRD)

- **Context:** o PRD exige a arquitetura de delegação e o modelo de ameaças (G2). Não há vault on-chain; a delegação é a carteira do bot com chave no KMS e assinatura isolada.
- **Decision:** adotar o STRIDE abaixo como critério do G2 desta intent; cada mitigação vira requisito não funcional e teste.

| Ameaça (STRIDE) | Cenário | Mitigação |
|---|---|---|
| Falsificação | ativar, subir o modo ou subir o teto da carteira de outra pessoa, ou com sessão roubada, ou reapresentando um consentimento antigo | consentimento assinado pela carteira principal para ativar, subir de modo e subir o teto, com nonce, validade e os valores autorizados; mensagem própria, nunca a de login; o TxSigner confere a assinatura e recusa nonce repetido |
| Falsificação | depósito de origem desconhecida tratado como do usuário | origem desconhecida registrada, sinalizada e lançada à parte |
| Adulteração | transação trocada entre a cotação e a assinatura | política de conteúdo do TxSigner (ADR-004) |
| Adulteração | correção silenciosa do ledger | append-only; compensação; reconciliação com a rede |
| Adulteração | os dois RPCs discordam | vale o resultado finalizado; caso incerto fica ambíguo e pausa o REAL do usuário |
| Repúdio | usuário nega ter autorizado o modo automático | consentimento assinado guardado; SigningRecord e evento de auditoria por assinatura |
| Vazamento | chave exposta | chave não exportável no KMS; nunca em log, banco ou resposta |
| Vazamento | carteira principal exposta | só HMAC na trilha e nos depósitos; logs mascarados |
| Negação de serviço | RPC ou Jupiter fora | dois RPC; novas ordens pausam; saque continua quando o RPC volta; ordem ambígua não repete |
| Negação de serviço | KMS fora ou cota de assinaturas esgotada | ordens e saques ficam pendentes com aviso; nada é assinado fora do KMS; alerta |
| Negação de serviço | token sem liquidez para venda, ou com congelamento pelo emissor | avaliação do scanner antes da compra (autoridade de congelamento e liquidez); posição sem venda fica marcada e não conta como saldo livre |
| Elevação de privilégio | código da API assina transação arbitrária ou monta um contexto falso | só o TxSigner, processo próprio com credencial e banco próprios, pode assinar (ADR-010); destino de saque, teto e modo vêm da política dele, derivada de consentimentos assinados pelo usuário. Risco residual aceito: uma API invadida ainda pode comprar e vender dentro do teto autorizado, sem tirar fundos da carteira |
| Elevação de privilégio | administrador comprometido desvia fundos | não existe saque para endereço que não seja a carteira principal; a política do TxSigner não depende de entrada do administrador |
| Elevação de privilégio | perda da chave no KMS (exclusão) | exclusão de chave com espera mínima do KMS e alerta; saque antes de encerrar |
| Manipulação de mercado | ordem visível sendo explorada (MEV, sanduíche) | derrapagem máxima pequena por padrão (2%); saída mínima na política |

- **Consequences:** (+) o G2 fica cumprido nesta intent; (-) sem auditoria externa (G3 dispensado), a eficácia depende dos testes.
- **Alternatives Rejected:** adiar o modelo de ameaças.

## ADR-006: ChainWatcher como porta sem regra de negócio

- **Context:** a rede é usada pela execução (envio, confirmação) e pela carteira (saldos, entradas, saque, reconciliação).
- **Decision:** a ChainWatcher só fala com o RPC: recebe endereços e assinaturas de quem chama; consultas em intervalos (pendentes a cada 2 s); dois provedores; vale o resultado finalizado.
- **Consequences:** (+) sem dependência de domínio nem ciclo; (-) quem chama controla os intervalos.
- **Alternatives Rejected:** a ChainWatcher conhecer carteiras e usuários (criava dependência oculta com a BotWallet); conexão permanente (websocket) agora.

## ADR-007: Estado REAL do usuário na BotWallet

- **Context:** a barreira precisa de teto, modo de operação, derrapagem e pausa; a liberação precisa de um dono.
- **Decision:** a BotWallet guarda e expõe esses valores e a liberação por usuário.
- **Consequences:** (+) uma fonte da verdade; (-) a administração depende da BotWallet.
- **Alternatives Rejected:** guardar na conta (Identity).

## ADR-008: Regras do projeto alteradas por decisão do fundador

- **Context:** a intent contradiz regras vigentes.
- **Decision:** as mudanças abaixo são registradas pelo AI-DLC antes da Construction:

| Regra hoje | Mudança |
|---|---|
| REAL automático desligado até G2 e G3 (`project.md`) | REAL liberado por decisão do fundador nos três modos; G2 cumprido pelo ADR-005; G3 dispensado nesta fase |
| Autoaprovação não satisfaz G2/G3 (`team.md`) | o fundador é o revisor dos gates do REAL nesta fase |
| Nunca executar ordens no navegador após ativar o bot (`project.md`) | no modo Assinada o navegador só assina; montar, enviar e acompanhar ficam no servidor |
| Nunca gerar chave secreta no código de produção (`project.md`) | continua: a geração acontece dentro do KMS, pelo TxSigner |
| Nunca armazenar chave privada do usuário (`project.md`) | continua para a carteira principal; a chave da carteira do bot é do sistema, só no KMS |
| Nunca SDK Solana na produção (`project.md`) | continua: decodificação e serialização próprias, mínimas |
| Nunca aprovação genérica irrestrita (`project.md`) | continua: o modo automático é limitado por teto, limites, tokens e é revogável |
| Nunca movimentar fundos reais sem aprovação explícita (`project.md`) | continua: cada liberação é aprovada pelo fundador, com quarentena de 24 horas |

- **Consequences:** a Construction só começa com essas mudanças registradas.
- **Alternatives Rejected:** mudar as regras silenciosamente no código.

## ADR-009: Quem grava no ledger REAL

- **Context:** depósitos, saques, taxas de saque e aluguel de contas não são ordens.
- **Decision:** lançamentos REAL por tipo (depósito, saque, preenchimento de swap, taxa de rede, aluguel de conta, compensação). A RealExecution grava os do swap; a BotWallet grava depósitos, saques, suas taxas e as compensações da reconciliação. O saque tem entidade própria (Withdrawal) com estados e reserva do valor.
- **Consequences:** (+) saldo livre e reconciliação batem com a rede; (-) dois gravadores, cada um testado.
- **Alternatives Rejected:** só a RealExecution grava (deixava depósitos e saques sem lançamento).

## ADR-010: Assinatura num processo isolado

- **Context:** o backend é um monólito de um processo, com uma única credencial AWS; qualquer código do processo poderia chamar o KMS.
- **Decision:** o TxSigner roda como processo próprio, com credencial AWS própria que é a única com permissão de assinar e de criar chaves; a API e o worker não têm essa permissão. A BotWallet chama o TxSigner por uma interface interna autenticada (rede interna, credencial de serviço). A topologia exata (contêiner, função) é decidida na Units Generation e na Infrastructure Design.
- **Consequences:** (+) o raio de explosão de um bug ou invasão na API fica limitado à política do TxSigner; (-) um processo a mais para implantar e monitorar.
- **Alternatives Rejected:** assinatura dentro do processo da API protegida só por regra de arquitetura (ArchUnit).

## ADR-011: Privacidade das entidades novas

- **Context:** entidades novas guardam endereços e assinaturas.
- **Decision:** endereços de origem de depósitos ficam só como HMAC; a carteira principal nunca é gravada nas entidades REAL; o endereço da carteira do bot e as assinaturas são dados públicos da rede, necessários ao ledger e ao fiscal, e ficam ligados só ao identificador interno; na anonimização, a BotWallet encerra a carteira e desvincula a conta, como no ledger PAPER. A retenção segue o catálogo existente; o ledger REAL fica pelo prazo fiscal.
- **Consequences:** (+) o direito de eliminação é cumprido sem quebrar o ledger; (-) o endereço público da carteira do bot continua na rede.
- **Alternatives Rejected:** apagar o ledger REAL na anonimização.

## ADR-012: Ordens condicionais da Jupiter (Should)

- **Context:** a US3.4 pede stop e alvo que disparam sem o usuário online.
- **Decision:** no modo Automática nas saídas, stop e alvo são ordens condicionais da Jupiter criadas e assinadas pela carteira principal do usuário (o TxSigner não participa). A entidade ConditionalOrder (aberta, preenchida, cancelada) fica na RealExecution, criada só depois da barreira; a RealExecution consulta a API da Jupiter para detectar o preenchimento e grava no ledger REAL. Só o usuário pode cancelar uma condicional que ele assinou: o kill switch, o desligamento do REAL e o rebaixamento de modo marcam as abertas e avisam o usuário para cancelar. No modo Automática total, as saídas são ordens normais do bot, assinadas pelo TxSigner.
- **Consequences:** (+) o TxSigner não precisa permitir o programa de condicionais; o preenchimento entra no ledger; (-) o kill switch não cancela as condicionais do usuário, só avisa; depende da API de ordens condicionais da Jupiter.
- **Alternatives Rejected:** condicionais fora do sistema.

## ADR-014: Cancelamento por reconferência

- **Context:** desligar o REAL, rebaixar o modo e pausar por divergência nascem na BotWallet, que não pode depender da RealExecution (fecharia um ciclo).
- **Decision:** a RealExecution reconfere na BotWallet o modo e a pausa antes de assinar e antes de enviar; uma ordem ainda não enviada é cancelada nesse ponto. O kill switch, que nasce na Administration, chama o cancelamento direto.
- **Consequences:** (+) sem ciclo e sem fila de eventos nova; o efeito vale no máximo até o próximo passo da ordem; (-) uma ordem já enviada segue até o fim, como definido nas histórias.
- **Alternatives Rejected:** evento de domínio da BotWallet para a RealExecution (mais infraestrutura para o mesmo efeito).

## ADR-013: Mudanças no código existente

- **Context:** o código atual impõe o PAPER em várias camadas.
- **Decision:** a Construction muda, com testes:
  - o `AuditEventValidator` aceita assinatura, mint e carteira do bot só nos campos REAL tipados;
  - as regras ArchUnit passam a permitir HTTP só nos adaptadores da Jupiter, do RPC e da CoinGecko, e o SDK Solana continua banido da produção;
  - a lista fixa de fontes do `marketdata` passa a vir da configuração;
  - uma migração relaxa o `CHECK (mode = 'PAPER')`;
  - o modo de operação do usuário se chama `operationMode`, para não colidir com o `mode` REAL/PAPER;
  - a idempotência usa o mecanismo comum do projeto.
- **Consequences:** (+) as proteções atuais continuam onde não precisam mudar; (-) mudanças em testes de arquitetura existentes.
- **Alternatives Rejected:** desligar as regras de arquitetura para o REAL.
