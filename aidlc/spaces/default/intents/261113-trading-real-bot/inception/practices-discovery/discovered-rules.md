# Regras descobertas — Trading REAL (re-run)

Conversa: português (Brasil). Regras novas, só por acréscimo às de `aidlc/spaces/default/memory/project.md`: elas
deixam claro como as regras vigentes se aplicam nesta fase, depois das mudanças decididas pelo fundador no ADR-008
(`inception/domain-design/decisions.md`). Nenhuma repete uma regra que já está em `project.md`. A relação de cada
regra com a regra vigente está em [evidence.md](evidence.md).

## Mandated

- ALWAYS considerar, nesta fase, o gate G2 cumprido pelo modelo de ameaças da carteira do bot do ADR-005 e o gate G3 dispensado por decisão do fundador, que é o revisor único dos gates do REAL; com isso, o REAL automático pode ser liberado nos três modos por usuário, e só depois da liberação aprovada pelo fundador com quarentena de 24 horas. (origem: ADR-008, ADR-005)
- ALWAYS gerar e manter a chave da carteira do bot dentro do KMS, não exportável, e deixar só o serviço de assinatura isolado (TxSigner), com credencial própria, assinar com ela; nenhum código de produção gera, lê ou exporta essa chave. (origem: ADR-008, ADR-005, ADR-010)
- ALWAYS manter o saque para a carteira principal registrada disponível a qualquer momento, inclusive com o kill switch acionado, com o REAL desligado, sem a liberação vigente ou sem o novo aceite dos termos. (origem: ADR-008, ADR-005, contract-summary "Termos")

## Forbidden

- NEVER deixar o navegador montar ou enviar transação no modo Assinada; o navegador só assina, e montagem, envio e acompanhamento ficam no servidor. (origem: ADR-008)
- NEVER deixar o TxSigner assinar transação fora da política de conteúdo (programas permitidos, destinos, saída mínima e teto autorizado) nem saque para endereço que não seja a carteira principal registrada. (origem: ADR-004, ADR-005)
