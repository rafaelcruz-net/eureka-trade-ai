# Visão de negócio — MemeTrade Analytics (eureka-trader-ai)

## Propósito

O MemeTrade Analytics é um SaaS web de análise e de simulação de operações com tokens de memes da rede Solana. O
código atual implementa o **beta fechado, por convite, só no modo PAPER** (saldo virtual em USDC): nenhuma operação
usa dinheiro real, nenhuma transação é assinada ou enviada à rede e não há fornecedor de mercado real (as fontes são
simuladas, `sim-a` e `sim-b`). O PRD completo (`PRD_MemeTrade_Analytics_v1.3_SaaS.md`) prevê trading REAL, IA,
assinaturas e AWS; essas partes não existem no código.

## Atores

| Ator | Como entra | O que faz |
|---|---|---|
| Convidado (`BETA_USER`) | Link de convite de uso único + assinatura de mensagem com carteira Phantom ou Solflare | Aceita termos e aviso de risco, usa o scanner, cria e opera bots PAPER, vê P&L, ledger e reconciliação, envia feedback, vê a tela Conta |
| Administrador (`ADMIN`) | App administrativo; carteira deve estar em `ADMIN_WALLET_ADDRESSES` | Emite e revoga convites, consulta a trilha de auditoria (só API), liga/desliga o kill switch, vê o estado do sistema, lê o feedback |
| Sistema (`system`) | Jobs agendados | Coleta de mercado, ciclo dos bots, marcação, reconciliação, expurgos de retenção |

## Funcionalidades principais (por Unit de entrega)

| Unit | Funcionalidade |
|---|---|
| U1 | Esqueleto: entrada por assinatura de carteira, sessão, lista mínima do scanner |
| U2 | Trilha de auditoria append-only, consulta administrativa, retenção de 12 meses |
| U3 | Convites, criação de conta com aceite de termos, sessões, tela Conta |
| U4 | Descoberta: duas fontes simuladas, scanner com filtros, avaliação de risco por pesos |
| U5 | Bots PAPER e motor de decisão determinístico, canal ao vivo |
| U6 | Barreira de risco (limites autorizados) e consulta de recusas |
| U7 | Ledger PAPER append-only, posições, P&L, reconciliação |
| U8 | Administração (kill switch, estado), entrada do administrador, feedback |
| U9 | Painel do convidado em tempo real |

O detalhe de cada componente está em [component-inventory.md](component-inventory.md) e as rotas em
[api-documentation.md](api-documentation.md).

## Regras de negócio que moldam o código

- REAL e PAPER estritamente separados; REAL sempre `UNAVAILABLE` no beta (CHECK `mode = 'PAPER'` em todas as tabelas financeiras).
- Dado de mercado ausente é `unknown`, nunca zero (tipo próprio no Java e no TypeScript).
- Ledger append-only, corrigido só por lançamento compensatório (gatilhos no banco).
- Idempotência por chave em toda escrita sensível, com mecanismo comum do módulo `identity`.
- Tenant vem sempre do token, nunca do corpo; única exceção nomeada: a emissão de convite pelo administrador.
- A assinatura de login só identifica; nunca autoriza trading.
- Valores monetários em decimal exato (`numeric`), nunca ponto flutuante (regra ArchUnit).

## Contexto do intent `termos-lgpd`

O que existe hoje, relevante para termos v2, política de privacidade LGPD, pedidos de titulares e anonimização:

- **Termos e aviso de risco**: só a versão `v1`, marcada "Versão provisória, pendente de revisão jurídica", em
  `apps/api/src/main/resources/terms/v1/`. O texto de `v1` declara que se guarda o endereço público da carteira, o
  provedor, a versão dos termos e o horário do aceite, ligados à conta e ao convite.
- **Aceite**: exigido só na criação da conta por convite; quem já tem conta nunca reaceita uma versão nova.
- **Tela Conta**: mostra carteira mascarada, provedor, fim da sessão, versão aceita, horário e o texto aceito.
- **Política de privacidade, rotas de titular (acesso, correção, exclusão, portabilidade), anonimização e
  encerramento de conta**: inexistentes no código. O PRD só recomenda avaliar a LGPD com assessoria jurídica (§18).
- **Retenções automáticas existentes**: trilha de auditoria 12 meses; saída da auditoria 7 dias após entrega;
  feedback 180 dias; decisões HOLD/NO_TRADE 30 dias; avaliações aprovadas da barreira 30/31 dias; registros de
  idempotência até expirar; reconciliações (últimas 100 por pessoa). Sem expurgo: carteira, contas, aceites,
  desafios de login, sessões, convites, ledger, ordens, posições, bots.

O inventário de dados pessoais por tabela, com retenção e travas, está em [architecture.md](architecture.md#dados-pessoais-e-retenção).
