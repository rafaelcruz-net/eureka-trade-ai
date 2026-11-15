# Units de trabalho — Trading REAL

Conversa: português (Brasil). Fontes: `inception/domain-design/components.md`, `inception/domain-design/decisions.md` (ADR-001 a ADR-014), `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md` e `units-generation-questions.md` (Q1 a Q5, plano aprovado).

## Units

| Unit ID | Directory | Kind | Complexidade | Implantação |
|---|---|---|---|---|
| U1 | `u1-bot-wallet` | service | L | monólito + processo TxSigner separado |
| U2 | `u2-real-market-data` | service | M | monólito |
| U3 | `u3-real-risk` | service | M | monólito |
| U4 | `u4-real-execution` | service | L | monólito (worker) |
| U5 | `u5-real-operations` | service | L | monólito |
| U6 | `u6-real-modes-ui` | ui | L | apps web e admin |
| U7 | `u7-exits-and-fiscal` | service | M | monólito |

## U1 — Carteira do bot (esqueleto)

- **Descrição:** a menor fatia integrada que funciona de verdade: ativação da carteira do bot com consentimento assinado, TxSigner (processo próprio, credencial e banco próprios, KMS simulado no ambiente local) criando a chave e mantendo a WalletPolicy, detecção de depósito pelo validador local, lançamento no ledger REAL, saque para a carteira principal passando pela política de assinatura.
- **Componentes:** TxSigner, BotWallet (ativação, consentimentos, depósitos, saque, preferências básicas, marca de liberação RealRelease), ChainWatcher, Ledger (modo REAL e tipos depósito, saque, taxa, reserva), Audit (campos REAL tipados), Identity (conferência do consentimento e versão v3 dos termos).
- **Entrega:** uma tela mínima de ativação, endereço, saldo e saque (a interface completa fica na U6); a migração que relaxa o `CHECK (mode = 'PAPER')`; as mudanças de ArchUnit e do validador da auditoria.
- **Pré-condições mínimas da ativação (para o esqueleto provar a ativação real):** o aceite dos termos v3 em versão mínima (o catálogo de termos ganha a versão v3 e a ativação recusa quem não aceitou; a tela de novo aceite completa fica na U6) e a liberação do REAL em versão mínima (marca por usuário, gravada e auditada, conferida na ativação; a liberação pelo administrador com quarentena de 24 horas fica na U5). No esqueleto, só a conta do fundador é liberada, pelo perfil de teste.
- **Notas:** o REAL continua desligado por padrão; sem ordens nesta Unit; o teste de ponta a ponta roda no validador local.

## U2 — Dados de mercado reais

- **Descrição:** adaptador da CoinGecko atrás da interface existente, fontes por configuração, atribuição na tela do scanner, sinal de "fonte simulada ativa".
- **Componentes:** MarketData.
- **Notas:** testes com respostas gravadas, nunca rede real.

## U3 — Barreira REAL

- **Descrição:** regras REAL na RiskControl: limites só para compras (10% por ordem, perda do dia 20%, 20 ordens por dia), teto sobre saldo livre mais posições, posição sem preço, conferência sob trava, tokens elegíveis, lista de bloqueio, frescor e recusa de ordem REAL com dado vencido ou com fonte simulada ativa (regra da US6.3; o sinal vem da U2).
- **Componentes:** RiskControl, Ledger (leitura REAL), BotWallet (leitura de teto e pausa).
- **Notas:** testes antes do código (núcleo financeiro).

## U4 — Execução REAL

- **Descrição:** fila de decisões gravada no ciclo, consumidor com trava, cotação e swap pela Jupiter, assinatura pela BotWallet e TxSigner (Automática total) ou pela carteira do usuário (Assinada, prazo de 8 s), envio, confirmação, ordem ambígua, idempotência, reconferência de modo e pausa, cancelamento de ordens não enviadas, preenchimentos e taxas no ledger, P&L REAL; também o servidor da escolha de modo (US3.1): rota de troca de modo com consentimento assinado, conferência do nonce e da validade, gravação na BotWallet e aviso ao TxSigner.
- **Componentes:** RealExecution, Bots (registro da decisão), ChainWatcher, BotWallet (preferências de modo), TxSigner (política de swap e de modo), Pnl (P&L REAL), Ledger.
- **Notas:** swaps testados no validador local com contas clonadas; testes antes do código para estados e idempotência.

## U5 — Operação REAL

- **Descrição:** reconciliação a cada 15 min e depois de cada ordem, pausa por divergência, liberação por usuário com quarentena de 24 horas (amplia a marca mínima da U1), desligar o REAL, kill switch REAL, anonimização com carteira do bot, área REAL do administrador.
- **Componentes:** BotWallet (reconciliação e liberação), Administration, RealExecution (cancelamento), Privacy.

## U6 — Telas e modos

- **Descrição:** tela de novo aceite dos termos v3 (a versão e a recusa no servidor vêm da U1), ativação em três passos, visão geral, tela de escolha do modo com consentimento assinado (o servidor vem da U4), faixa e diálogo de assinatura, ordens, depósito e saque, cartão REAL no painel; corrige os pontos de tela deixados em aberto nos mockups (campo do teto, estados, confirmações, cor do REAL).
- **Componentes:** apps web e admin; Identity (termos v3).

## U7 — Saídas automáticas e exportação fiscal (Should)

- **Descrição:** ordens condicionais da Jupiter assinadas pela carteira principal, detecção do preenchimento, aviso ao usuário quando o kill switch da U5 é acionado com condicionais abertas (FR8.2); exportação do histórico REAL.
- **Depende de:** U1 (ledger REAL), U4 (execução) e U5 (kill switch).
- **Componentes:** RealExecution (ConditionalOrder), Ledger, Pnl.
