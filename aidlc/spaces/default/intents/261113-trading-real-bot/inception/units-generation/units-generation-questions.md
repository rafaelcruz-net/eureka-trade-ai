# Perguntas de Geração de Units — Trading REAL

Conversa: português (Brasil). Base: `inception/domain-design/components.md`, `inception/domain-design/decisions.md`, `inception/requirements-analysis/requirements.md` e `inception/user-stories/stories.md`.

## Q1. [Arquitetura] Como dividir as Units?

A. Por fatia de funcionalidade de ponta a ponta, respeitando os componentes: carteira do bot com assinatura isolada; dados reais; barreira REAL; execução REAL; operação (reconciliação, liberação, kill switch, anonimização); telas e modos; saídas automáticas e exportação fiscal
B. Uma Unit por componente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] Qual é a primeira Unit (esqueleto), a menor fatia integrada que funciona de verdade?

A. Carteira do bot de ponta a ponta no validador local: ativar com consentimento assinado, o TxSigner criar a chave (KMS simulado localmente), detectar um depósito, lançar no ledger REAL e sacar para a carteira principal com a política de assinatura; isso prova a assinatura isolada, a rede e o ledger REAL antes de qualquer ordem
B. Um swap da Jupiter primeiro
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Arquitetura] Qual granularidade?

A. Sete Units de tamanho M ou L, cada uma entregável e testável sozinha
B. Três Units grandes
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Arquitetura] Como é a implantação?

A. O monólito existente (API e worker) recebe os módulos novos; o TxSigner é um processo separado, com credencial e banco próprios, no mesmo Compose local e implantado à parte na nuvem
B. Tudo no monólito
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Arquitetura] Units independentes podem andar em paralelo?

A. Sim no grafo (por exemplo, dados reais em paralelo à carteira), mas a construção segue uma Unit por vez, como nas intents anteriores
B. Só em ordem estrita
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
