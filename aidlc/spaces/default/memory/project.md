# Project-Level Rules

> Project-specific specialisation and corrections. Loaded after `org.md` and
> `team.md` as strict-additive guidance; contradictions with broader policy
> are rejected. Populated by practices-discovery and the self-learning loop.
>
> Use sparingly: most teams don't need a project layer. Reach for it
> only when this specific project needs stable, durable guidance beyond the
> team practice (for example, package-specific release checks or an additional
> regression suite for a legacy component).

## Way of Working

<!-- Project-specific specialisation. Example: -->
<!-- This monorepo requires package-scoped branch names and a package owner -->
<!-- review in addition to the team's normal merge policy. -->

## Walking Skeleton

<!-- Project-specific specialisation. Example: -->
<!-- The walking skeleton must exercise the legacy service adapter as well -->
<!-- as the new service boundary. -->

## Testing Posture

<!-- Project-specific specialisation. -->

## Guard Policy

<!-- Project-specific. Mode: strict, relaxed, or off. Strict here holds for every intent, whatever one person asks in chat; relaxed or off here applies to every intent whose policy came from its scope, unless the person sets another; changing this line changes it. A section under the retired Change Control heading, written by an earlier release, is still read. -->

## Deployment

<!-- Project-specific specialisation. -->

## Code Style

<!-- Project-specific specialisation. -->

## Tech Stack

<!-- Technology choices locked for this project. -->

## Decided

<!-- Decisions made in earlier stages that should not be re-asked. -->
<!-- Format: DECIDED: [decision] (Stage [slug], [date]) -->

## Scope Overrides

<!-- Custom scope rules for this project. -->

## Forbidden

<!-- Populated by practices-discovery affirmation gate. -->
<!-- Format: NEVER [behavior] (affirmed [date]) -->
<!-- Example: NEVER throw exceptions across service layer boundaries (affirmed 2026-05-17) -->

- NEVER solicitar, receber ou armazenar seed phrase, chave privada ou aprovação genérica irrestrita do usuário. (origem: C-02, PRD §7 e §16) (affirmed 2026-10-26)

- NEVER tratar o login por mensagem assinada como autorização para operar. (origem: C-02) (affirmed 2026-10-26)

- NEVER executar ordens no navegador depois que o bot é ativado. (origem: PRD §12) (affirmed 2026-10-26)

- NEVER classificar um token com dado `unknown` como seguro nem exibir score quando a cobertura for insuficiente. (origem: C-16, PRD §9) (affirmed 2026-10-26)

- NEVER usar dados futuros em treinamento ou backtest. (origem: PRD §5) (affirmed 2026-10-26)

- NEVER movimentar fundos reais ou implantar contratos on-chain sem aprovação explícita do responsável do projeto. (origem: PRD §19, initiative brief) (affirmed 2026-10-26)

- NEVER prometer lucro ou garantia de detecção de fraude em telas ou comunicações. (origem: C-16) (affirmed 2026-10-26)

- NEVER usar ponto flutuante para valores monetários, quantidades de token ou P&L; usar decimal exato ou inteiros em unidades base. (origem: PRD §10 e métricas do beta) (affirmed 2026-10-26)

- NEVER registrar em log segredos, tokens de sessão, cabeçalhos `Authorization`, nonces ou assinaturas de login. (origem: PRD §12, PRD §15 US15) (affirmed 2026-10-26)

- NEVER expor segredo em variável `NEXT_PUBLIC_*` ou em qualquer código entregue ao navegador. (origem: PRD §12, C-02) (affirmed 2026-10-26)

- NEVER incluir no código de produção APIs de importação ou geração de chave secreta ou de frase mnemônica. (origem: C-02, PRD §7) (affirmed 2026-10-26)

- NEVER incluir o SDK Solana no código de produção; ele fica só no código de teste, e a produção confere a assinatura com Ed25519 nativo do Java e uma decodificação Base58 própria. (learned 2026-10-30) <!-- cid:261026-memetrade-saas-mvp:nfr-requirements:1f680480a2d9448ad50732994f56a261c1e8eee90fdea88e1ac5f163c77a5802 -->

- NEVER deixar o navegador montar ou enviar transação no modo Assinada; o navegador só assina, e montagem, envio e acompanhamento ficam no servidor. (origem: ADR-008) (affirmed 2026-11-14)

- NEVER deixar o TxSigner assinar transação fora da política de conteúdo (programas permitidos, destinos, saída mínima e teto autorizado) nem saque para endereço que não seja a carteira principal registrada. (origem: ADR-004, ADR-005) (affirmed 2026-11-14)

## Mandated

<!-- Populated by practices-discovery affirmation gate. -->
<!-- Format: ALWAYS [behavior] (affirmed [date]) -->
<!-- Example: ALWAYS use Result<T,E> for fallible operations in service layer (affirmed 2026-05-17) -->

- ALWAYS manter REAL e PAPER estritamente separados em fundos, ordens, ledger, posições e P&L. (origem: C-03, PRD §15 US12) (affirmed 2026-10-26)

- ALWAYS manter o trading REAL automático desabilitado até que os gates G2 e G3 do PRD e as aprovações jurídicas sejam cumpridos; PAPER continua operacional. (origem: C-01, C-09, PRD §7 e §16) (affirmed 2026-10-26)

- ALWAYS representar dado de mercado ausente como `unknown`, nunca como zero, exibindo fonte, timestamp, frescor e cobertura. (origem: C-05, PRD §4) (affirmed 2026-10-26)

- ALWAYS deduplicar operações por chave de idempotência e verificar os limites autorizados e os controles de risco antes de cada ordem, inclusive em PAPER. (origem: C-04, PRD §14) (affirmed 2026-10-26)

- ALWAYS validar o tenant no servidor em toda leitura e escrita de dados de cliente. (origem: PRD §11 e §16) (affirmed 2026-10-26)

- ALWAYS registrar o ledger como append-only, corrigindo erros apenas com lançamentos compensatórios. (origem: PRD §13) (affirmed 2026-10-26)

- ALWAYS gravar e trafegar instantes em UTC, convertendo para o fuso do usuário só na interface. (origem: revisão do desenvolvedor) (affirmed 2026-10-26)

- ALWAYS bloquear commits e merges que contenham segredos, com varredura no pré-commit e na CI. (origem: PRD §12, fase Construction) (affirmed 2026-10-26)

- ALWAYS versionar os lockfiles e instalar as dependências a partir deles na CI. (origem: cadeia de suprimentos, PRD §12) (affirmed 2026-10-26)

- ALWAYS fixar pelo SHA do commit as ações de terceiros usadas na CI. (origem: integridade da CI) (affirmed 2026-10-26)

- ALWAYS considerar, nesta fase, o gate G2 cumprido pelo modelo de ameaças da carteira do bot do ADR-005 e o gate G3 dispensado por decisão do fundador, que é o revisor único dos gates do REAL; com isso, o REAL automático pode ser liberado nos três modos por usuário, e só depois da liberação aprovada pelo fundador com quarentena de 24 horas. (origem: ADR-008, ADR-005) (affirmed 2026-11-14)

- ALWAYS gerar e manter a chave da carteira do bot dentro do KMS, não exportável, e deixar só o serviço de assinatura isolado (TxSigner), com credencial própria, assinar com ela; nenhum código de produção gera, lê ou exporta essa chave. (origem: ADR-008, ADR-005, ADR-010) (affirmed 2026-11-14)

- ALWAYS manter o saque para a carteira principal registrada disponível a qualquer momento, inclusive com o kill switch acionado, com o REAL desligado, sem a liberação vigente ou sem o novo aceite dos termos. (origem: ADR-008, ADR-005, contract-summary "Termos") (affirmed 2026-11-14)

## Corrections

<!-- Project-specific corrections from human feedback. -->
<!-- Format: NEVER/ALWAYS [behavior] (learned [date]) -->
- Separar oferta pública de alternativa efetivamente usada pelos convidados; validar a rotina dos usuários em pesquisa antes de tratar uma ferramenta como concorrente confirmado. (learned 2026-10-26) <!-- cid:261026-memetrade-saas-mvp:market-research:76550d2968b9e6292fca7074ed0b6882121c7aa6a001f840d9865826589655a2 -->
- Priorizar cobertura e frescor dos dados na pesquisa de mercado; manter a ordem secundária dos critérios como hipótese até validá-la com usuários. (learned 2026-10-26) <!-- cid:261026-memetrade-saas-mvp:market-research:fd94b48c3fc2e6212c4327038247cc561ec51a201eef445d082810982f99a84f -->
- Tratar o "Must mais enxuto" do beta como núcleo descoberta → avaliação → PAPER → P&L reconciliável; manter isolamento, permissões e auditoria como base Must e deixar filtros salvos, alertas e métricas avançadas como Should. (learned 2026-10-26) <!-- cid:261026-memetrade-saas-mvp:scope-definition:ea6e38abe3e141510174d8bade99b4d4253906657a58ab6b4c6b1858ad7a626b -->
- No beta, tratar acessibilidade básica como navegação por teclado, foco visível, rótulos visíveis e contraste legível no tema escuro, sempre com cor acompanhada de texto ou ícone; exigir WCAG 2.1 AA completo antes de abrir ao público. (learned 2026-10-26) <!-- cid:261026-memetrade-saas-mvp:rough-mockups:b4b2c90a26ef239f63fd50e7e80fd46ad84c2f6d56e0e8a4e4295f44e41cef6c -->
- Na Geração de Unidades, quando o texto do plano aprovado não estiver gravado, montar as Units a partir da lista de Units da última resposta que fixou a quantidade (Q13 neste projeto: esqueleto, auditoria, identidade, descoberta, bots e decisão, barreira, ledger e P&L, administração com feedback, painel). (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:units-generation:f16264147846e9fd928a7f4125eecdc3ac669a304b3ced32d6978e5b2d60742d -->
- No Contract Design deste monólito modular, tratar as interfaces Java públicas entre módulos como contratos de esquema compartilhado, ao lado do OpenAPI (REST) e do AsyncAPI (eventos). (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:contract-design:1e6822e0921e9de7da0ff41365913cc94938b244058c95077933b789bd61b2b4 -->
- Com token de acesso na memória e renovação por cookie SameSite=Strict, manter a API e os dois apps web no mesmo site e abrir o canal ao vivo do painel com fetch em leitura contínua, não com EventSource. (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:contract-design:8237ba335f4d533c5ebd6e9b750569a841ee3d90bdeb05ac85cace36da7ca7c1 -->
- Com o papel ADMIN restrito à entrada administrativa, o smoke do esqueleto entra com uma conta BETA_USER semeada só no perfil de teste, com carteira de teste gerada no código de teste e nunca no código de produção. (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:contract-design:dfc151ed4d55c9320dbd68125c860e7e276b82bd6913727ac9f0bc475e386f39 -->
- No planejamento de entrega, quando houver uma estratégia por fases e uma pontuação WSJF, usar a estratégia para definir as fases e o WSJF para ordenar dentro de cada fase, respeitando o grafo de dependências. (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:delivery-planning:dd6cc730b78453bff57f6ef7be53383fd57ce9840fc392c369bc5f02b6302c17 -->
- Em empate próximo no WSJF, preferir a Unit que destrava o núcleo financeiro (por exemplo, U4 antes de U3) e registrar o desvio na justificativa da ordem. (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:delivery-planning:c15ccee05ca9888bc8f2b51e38b309764f68d9a5d5c7e1584fe5797a84f6af1a -->
- Quando a pessoa lista Units de uma mesma fase sem dizer que a ordem importa, ordenar essas Units pelo WSJF e registrar a ordem aplicada. (learned 2026-10-27) <!-- cid:261026-memetrade-saas-mvp:delivery-planning:fde591c7bd490e46981484a81ad95499aa224ddcd0235d66236d9463d7ad0276 -->
- Usar o mecanismo comum de idempotência do módulo de identidade por interface em toda Unit nova, em vez de cada Unit ter o seu. (learned 2026-10-30) <!-- cid:261026-memetrade-saas-mvp:functional-design:57e114ffd1e70198bd175e5d87afaee30cd18045545e3092d416c182c459cd6c -->
- Na Geração de Código, escrever o traceability.json já no formato do verificador de rastreabilidade: IDs NFRx.y sem o prefixo da Unit, uma linha por AC, BR e NFR da Unit e um único arquivo existente por alvo OK, com os demais arquivos e testes numa nota. (learned 2026-11-20) <!-- cid:261113-trading-real-bot:build-and-test:5dc20de0de78865e335888d210c885b73b28f7b2bfa7ef9d5e0b86eed04a6b7f -->
- Na observabilidade na nuvem, o CloudWatch Agent lê as métricas a cada 60 s, para manter o custo de ingestão baixo; alarmes que disparam com dado ausente usam janela de pelo menos duas leituras. (learned 2026-11-20) <!-- cid:261113-trading-real-bot:observability-setup:45bb3abfcc3ed8faedb44032d9cc70aeba0d5fc20f9255a81041472a9a9db1b9 -->
- Correção da regra anterior de 60 s: na observabilidade na nuvem, o CloudWatch Agent lê as métricas a cada 120 s; alarmes que disparam com dado ausente usam janelas múltiplas de 240 s (duas leituras) e os demais pelo menos 180 s (uma leitura); o orçamento mensal de cada conta é de US$ 350. (learned 2026-11-20) <!-- cid:261113-trading-real-bot:observability-setup:7f2ff85588c0c2b8b423268bc45244d391f23183f1a676dfc213f82b9e07e92f -->
