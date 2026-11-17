# Projeto de segurança — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `construction/u7-exits-and-fiscal/nfr-requirements/security-requirements.md` (U7-NFR1.x, U7-NFR2.1, U7-NFR7.1) e `nfr-design-questions.md` (Q1, Q4, Q6).

| Requisito | Como o projeto atende |
|---|---|
| U7-NFR1.1 | A `TriggerPolicy` lê a transação com o leitor do TxSigner e confere, nesta ordem: <br>1. programas e discriminadores contra o `trigger-program.json`; <br>2. a `maker` e o pagador iguais à carteira do bot; <br>3. a conta de custódia igual à derivada pelas sementes gravadas; <br>4. mint, quantidade e destino da saída; <br>5. os signatários; <br>6. a saída mínima contra o piso do pedido, quando existir. <br>Cada recusa devolve o motivo, e os testes de ataque usam as transações gravadas como base e alteram um campo por vez. |
| U7-NFR1.2 | A `TriggerPolicy` só é registrada se o `trigger-program.json` existir e o hash conferir; sem ela, o `/signatures` recusa com `POLICY_NOT_RECORDED`. |
| U7-NFR1.3 | O `TRIGGER_CANCEL` usa o mesmo caminho do saque no TxSigner: não consulta kill switch, liberação nem REAL ligado. |
| U7-NFR1.4 | A `TriggerValidation` aplica BR3.4 e BR2.6 antes de chamar a Jupiter e o TxSigner. |
| U7-NFR1.5 | A chave da Jupiter vem de `MEMETRADE_JUPITER_API_KEY`, que existe só no worker e na API. O `RestClient` mascara o cabeçalho, e o ArchUnit restringe a rede a `infra.jupiter`. |
| U7-NFR1.6, U7-NFR2.1 | A `FiscalExportController` pega o tenant e a pessoa do `AuthenticatedPrincipal`, e a M2 filtra por eles. As colunas não incluem a carteira principal. Uma regra ArchUnit proíbe o acesso de `realexits` ao PAPER. |
| U7-NFR1.7 | O `FiscalCsvWriter` aplica o escape só nos campos de texto. Os números saem de `BigDecimal.toPlainString()` com vírgula decimal. |
| U7-NFR1.8 | Regras ArchUnit: rede só em `realexits.infra.jupiter`, nenhuma chamada de assinatura fora do cliente do TxSigner, e `realexecution` e `botwallet` sem importar `realexits`. Cada regra tem uma fixture que falha. |
| U7-NFR1.9 | As rotas de criação e de `DELETE` usam o `IdempotencyGuard` comum (novas operações em `IdempotentOperation`) e o tenant do token. |
| U7-NFR7.1 | Preços em `BigDecimal` no domínio e `numeric` no banco; quantidades em unidades base (`BigInteger` e `numeric(39,0)`). |
