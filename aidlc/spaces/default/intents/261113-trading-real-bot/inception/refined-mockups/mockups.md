# Mockups refinados — Trading REAL

Conversa: português (Brasil). Fontes: `inception/user-stories/stories.md` (US1.1 a US8.5), `inception/requirements-analysis/requirements.md` e `refined-mockups-questions.md` (Q1 a Q7). Não há wireframes nem fluxo de usuário desta intent (os mockups rascunho foram pulados); as telas partem das existentes em `apps/web` e `apps/admin` e do vocabulário visual do intent `261026-memetrade-saas-mvp`.

## Navegação (app do convidado)

```
┌─────────────────────────────────────────────────────────────────┐
│ [Faixa: Ordem para assinar · PEPE · 0,2 SOL · 0:08  [Ver] ]     │  ← só no modo Assinada, quando houver proposta
├──────────────┬──────────────────────────────────────────────────┤
│ Painel       │                                                  │
│ Scanner      │   (conteúdo da tela)                             │
│ Bots         │                                                  │
│ P&L          │                                                  │
│ Carteira do  │                                                  │
│  bot  [REAL] │                                                  │
│ Conta        │                                                  │
└──────────────┴──────────────────────────────────────────────────┘
```

## R1 — Ativação da carteira do bot (US1.1, US1.2)

```
Carteira do bot                                         [REAL]
──────────────────────────────────────────────────────────────
Passo 1 de 3 · Termos do REAL
  [texto dos termos v3 com rolagem]
  ☐ Li e aceito os termos do REAL (posso perder todo o valor depositado)
  [Continuar]  (desabilitado até marcar; motivo em texto)

Passo 2 de 3 · Consentimento
  Você vai assinar na sua carteira uma mensagem que autoriza a carteira do bot.
  Esta assinatura não é a do login e não movimenta fundos.
  [Assinar na carteira]

Passo 3 de 3 · Pronto
  Endereço da carteira do bot: 7xKX…9fQa  [Copiar]
  Próximo: defina o teto e deposite.  [Ir para Depósito e saque]
```
Estados: REAL não liberado para a conta ("O REAL ainda não está disponível para sua conta"); carregando; erro com "tentar de novo"; assinatura recusada na carteira.

## R2 — Visão geral (US2.1, US2.2, US4.2)

```
Carteira do bot · Visão geral                                   [REAL]
──────────────────────────────────────────────────────────────────────
 Saldo livre        Reservado em ordens    Reserva para taxas    Acima do teto
 1,250000000 SOL    0,200000000 SOL        0,010000000 SOL       0,000000000 SOL

 Teto: 3 SOL  (máximo 10)   Exposição atual: 2,1 SOL   Perda hoje: -0,12 SOL / limite 0,60
 Modo: Assinada  [Trocar modo]
 P&L REAL (com taxas): +0,084 SOL   ← separado do PAPER
 Última reconciliação: há 4 min · sem divergência
```
Estados: REAL pausado (faixa com o motivo: perda do dia, divergência, ordem em conferência, kill switch); preço `unknown` de uma posição (valor pelo custo de compra, com rótulo "sem preço").

## R3 — Modo de operação (US3.1)

```
Como o bot opera com dinheiro real?
 ( ) Assinada (atual)          Você assina cada ordem na sua carteira.
 ( ) Automática nas saídas     Em breve.
 ( ) Automática total          O bot opera sozinho pela carteira do bot, dentro do teto.
 [Salvar]
```
Subir de modo abre o diálogo de consentimento com assinatura; voltar para Assinada vale na hora.

## R4 — Ordem para assinar (US3.2)

Diálogo aberto pela faixa:
```
Assinar ordem · expira em 0:08
 Comprar PEPE · 0,2 SOL
 Preço estimado: 0,0000123 SOL   Derrapagem máxima: 2%   Taxa da rede: 0,000005 SOL
 Por que: volume 24h subiu 40% e liquidez acima do mínimo
 [Assinar na carteira]   [Recusar]
```
Ao expirar: "A proposta expirou. Nada foi enviado."

## R5 — Ordens (US4.1)

```
Data (seu fuso)   Token   Lado    Quantidade    Preço        Taxa       Estado            Transação
14/11 10:02       PEPE    Compra  16.260 PEPE   0,0000123    0,000005   ✔ Confirmada      ↗ ver
14/11 09:40       BONK    Venda   …             …            …          ⟳ Em conferência  ↗ ver
```
Estados: vazio ("Nenhuma ordem real ainda"), carregando, erro, paginação por cursor.

## R6 — Depósito e saque (US2.2, US5.1, US5.2)

```
Depositar: envie SOL da sua carteira principal para 7xKX…9fQa [Copiar]
  O bot opera até o teto; o que passar dele fica disponível só para saque.

Sacar para: AbCd…WxYz (sua carteira principal)
  Disponível para saque: 1,250000000 SOL   Reservado em ordens: 0,200000000 SOL
  [Valor]  [Sacar]     [Vender tudo e sacar]
```
Confirmação nomeia valor e destino. Kill switch acionado: o saque continua disponível, com aviso.

## A1 — REAL no app do administrador (US7.1, US7.2, US7.3, US8.1)

```
REAL · Usuários
 Usuário        Modo         Teto   Saldo REAL   Liberação                Divergência
 fundador       Automática   10     8,4 SOL      Ativa                    —
 convidado-07   Assinada     3      1,4 SOL      Em quarentena (14h restantes)  —
 [Detalhe] → Liberar / Desligar REAL / Baixar teto

REAL · Tokens bloqueados   [+ Bloquear token]
Kill switch: ligado → "Novas ordens REAL bloqueadas para todos; saques liberados"
```

## Cobertura das histórias

| Tela | Histórias |
|---|---|
| R1 | US1.1, US1.2 |
| R2 | US2.1, US2.2, US4.2, US8.2 |
| R3 | US3.1 |
| R4 | US3.2 |
| R5 | US4.1, US8.3 |
| R6 | US2.2, US5.1, US5.2 |
| A1 | US6.2, US7.1, US7.2, US7.3, US8.1 |
