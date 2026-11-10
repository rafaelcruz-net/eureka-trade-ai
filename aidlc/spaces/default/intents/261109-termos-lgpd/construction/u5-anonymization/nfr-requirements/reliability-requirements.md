# Requisitos de confiabilidade e acessibilidade — U5 `u5-anonymization`

Conversa: português (Brasil). Fontes: `construction/u5-anonymization/functional-design/` (BR5.2, BR5.3, BR5.5, BR5.6, BR5.9, F2), `inception/requirements-analysis/requirements.md` (NFR4, NFR5) e a regra de acessibilidade básica do beta.

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR5.1 | O ledger, as ordens, os fills, as recusas, os aceites e a trilha não mudam. A reconciliação dá 0 divergência depois da anonimização | teste de integração que compara as linhas antes e depois e roda a reconciliação |
| NFR5.2 | Uma falha simulada em qualquer ponto de escrita desfaz tudo: a conta continua ativa, a carteira continua, os bots e o feedback ficam como estavam e o pedido não é concluído. Os cinco pontos de escrita do F2 são: J2 (bots parados), J3 (sessões revogadas), J1 (conta, carteira e desafios de login), J6 (`ACCOUNT_ANONYMIZED` na trilha) e a conclusão do pedido com `DATA_REQUEST_TRANSITIONED` | teste com falha injetada em cada um dos cinco pontos |
| NFR5.3 | A migração `0015-anonymization.sql` só acrescenta ou relaxa: o `CHECK` de status com `ANONYMIZED`, a carteira com endereço e provedor anuláveis e `anonymized_at`, o `CHECK` que exige endereço e provedor ausentes só quando `anonymized_at` está preenchido, o `CHECK` de Base58 que vale só com endereço presente, o índice `login_challenge_wallet_idx` e a função `SECURITY DEFINER`; o gatilho de feedback não muda. Cada changeset tem `--rollback` válido, aplicável enquanto nenhuma conta foi anonimizada | teste que lê a migração; teste de integração que aplica a migração, roda o rollback de cada changeset numa base sem conta anonimizada e verifica os dois `CHECK` com linhas válidas e inválidas |
| NFR5.4 | Uma trava não obtida dentro do `lock_timeout` de 2 s (o mesmo de `LockOrder`, NFR10.7 do intent anterior) responde `503` e não muda nada; a repetição com a mesma chave pode concluir depois | teste de integração com uma trava segura por outra transação por mais de 2 s |
| NFR5.5 | Duas anonimizações concorrentes do mesmo pedido produzem uma única mudança e um único evento; a segunda devolve o mesmo resultado | teste de integração concorrente |
| NFR5.6 | Login, renovação e ciclo de bot concorrentes com a anonimização seguem a ordem de travas (bots, sessões, conta) e nunca deixam sessão ou ordem nova depois dela | teste de integração concorrente para cada caminho |
| NFR4.1 | O diálogo T8 é acessível:<ul><li>funciona só pelo teclado e prende o foco;</li><li>Esc fecha e devolve o foco;</li><li>o campo tem rótulo visível;</li><li>o motivo do botão desabilitado aparece em texto;</li><li>as mensagens ficam em `aria-live`;</li><li>a cor vem sempre com texto.</li></ul> | teste de componente |
| NFR4.2 | Os demais itens do NFR4 ("Pular para o conteúdo", foco visível, títulos h1 a h3 em ordem e regiões de página) são herdados das telas de pedidos da U4, onde o diálogo T8 abre; a U5 não cria tela nova e não pode quebrá-los | os testes de layout e de acessibilidade da U4 continuam verdes |
