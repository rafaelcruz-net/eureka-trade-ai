# Mapa de histórias por Unit — Trading REAL

Conversa: português (Brasil). Fontes: `inception/user-stories/stories.md`, `unit-of-work.md`, `inception/domain-design/components.md` e `inception/requirements-analysis/requirements.md`.

| História | Unit | Observação |
|---|---|---|
| US1.1 | U6 | tela de novo aceite dos termos v3; a versão v3 e a recusa no servidor vêm da U1 |
| US1.2 | U1 | ativação com consentimento e TxSigner, com aceite v3 e liberação em versão mínima |
| US2.1 | U3 | teto no perfil e regra do teto na barreira |
| US2.2 | U1 | depósito detectado e excedente acima do teto |
| US3.1 | U6 | tela de escolha do modo; o servidor (consentimento e troca de modo) fica na U4 |
| US3.2 | U4 | assinatura no modo Assinada (servidor); a faixa e o diálogo ficam na U6 |
| US3.3 | U4 | execução automática pela carteira do bot |
| US3.4 | U7 | saídas automáticas, com aviso no kill switch da U5 |
| US4.1 | U4 | ordens e estados (dados); as telas ficam na U6 |
| US4.2 | U4 | P&L REAL (componente Pnl) e ledger REAL dos swaps |
| US4.3 | U7 | exportação fiscal |
| US5.1 | U1 | saque para a carteira principal |
| US5.2 | U4 | vender tudo e sacar |
| US6.1 | U3 | limites |
| US6.2 | U3 | tokens permitidos e bloqueio |
| US6.3 | U2 | dados reais, frescor e sinal de fonte simulada; a recusa da ordem fica na U3 |
| US7.1 | U5 | liberação com quarentena |
| US7.2 | U5 | baixar teto e bloquear token pelo administrador |
| US7.3 | U5 | estado do REAL no administrador |
| US8.1 | U5 | kill switch REAL |
| US8.2 | U5 | reconciliação |
| US8.3 | U4 | ordem ambígua |
| US8.4 | U5 | anonimização com carteira do bot |
| US8.5 | U1 | auditoria REAL tipada |

## Histórias que atravessam Units

- US3.1, US3.2 e US4.1: servidor na U4, telas na U6.
- US1.1 e US1.2: versão v3 e liberação mínima na U1; tela de aceite na U6; liberação com quarentena na U5.
- US6.3: dados e sinal na U2, recusa da ordem na U3.
- US2.1: regra na U3, campo do teto na U6.
- US8.5: base da auditoria na U1; cada Unit publica os seus eventos.

## Ordem dentro de cada Unit

- **U1:** TxSigner e WalletPolicy → termos v3 e marca de liberação mínimos → ativação e consentimento → depósito → saque → auditoria tipada.
- **U3:** limites → teto e posições sem preço → tokens e bloqueio → frescor e recusa com fonte simulada.
- **U4:** fila de decisões → estados e idempotência → cotação e swap → confirmação e ambígua → P&L REAL → troca de modo → Assinada → vender tudo e sacar.
- **U5:** liberação → reconciliação → kill switch → administrador → anonimização.

## Verificação

Todas as 24 histórias têm Unit; todas as 7 Units têm histórias.
