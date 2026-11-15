# Registro RAID — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md` e `feasibility-questions.md` (Q1 a Q13).

## Riscos

| ID | Risco | Probabilidade / Impacto | Mitigação | Responsável |
|---|---|---|---|---|
| R-01 | Comprometimento de chaves: todas as carteiras do bot expostas (até 210 SOL no pior caso) | Baixa / Crítico | Chaves só no KMS, não exportáveis; permissão de assinar restrita ao serviço executor; saque só para a carteira principal; tetos | Fundador |
| R-02 | O KMS escolhido não assina Ed25519 | Média / Alto | Verificar antes do Domain Design; alternativa: outro KMS ou HSM com Ed25519 | Fundador |
| R-03 | Bug no caminho de execução sem auditoria externa nem segundo revisor | Média / Alto | Testes obrigatórios na CI (limites, separação REAL/PAPER, idempotência, saque), devnet e validador clonado, carteira do fundador primeiro, quarentena de 24 horas | Fundador |
| R-04 | Enquadramento regulatório como custódia | Média / Alto | Parecer escrito recomendado antes dos convidados; termos e novo aceite | Fundador |
| R-05 | Derrapagem de preço, falha ou atraso de confirmação em memecoins voláteis | Alta / Médio | Derrapagem máxima por ordem, cotação na hora, acompanhamento da confirmação e registro de falha | Fundador |
| R-06 | Ordem duplicada por repetição após falha de rede | Média / Alto | Chave de idempotência por ordem e conferência da transação na rede antes de repetir | Fundador |
| R-07 | Divergência entre ledger REAL e blockchain | Média / Alto | Reconciliação periódica com alerta; correção só por lançamento compensatório | Fundador |
| R-08 | Indisponibilidade do RPC ou da Jupiter | Média / Médio | Provedor de reserva; o bot pausa novas ordens e as saídas condicionais continuam onde já estão na rede | Fundador |
| R-09 | Dados de mercado defasados levando a decisões ruins | Média / Médio | Fonte real com frescor exibido; dado ausente é `unknown`; o REAL não opera com dado vencido | Fundador |
| R-10 | Custos acima de US$ 100 por mês | Baixa / Baixo | Planos de entrada e alarmes de custo | Fundador |

## Premissas

| ID | Premissa | Como validar |
|---|---|---|
| A-01 | A Jupiter devolve transações que a produção consegue assinar sem SDK Solana | Prova no validador clonado antes da Construction |
| A-02 | Um validador local com contas da mainnet clonadas reproduz os swaps da Jupiter com fidelidade suficiente | Executar um swap de ponta a ponta |
| A-03 | O plano pago da CoinGecko permite exibir os dados aos usuários com atribuição | Confirmação por escrito do fornecedor |

## Questões em aberto (issues)

| ID | Questão | Decide |
|---|---|---|
| I-01 | Conta AWS ainda não existe | Fundador, antes da Infrastructure Design |
| I-02 | Parecer de custódia não obtido | Fundador, antes da liberação para convidados |
| I-03 | Três regras do projeto e uma do time precisam mudar (ver `feasibility-assessment.md`) | Fundador, pelo registro do AI-DLC antes da Construction |

## Dependências

| ID | Dependência | Para quê |
|---|---|---|
| D-01 | Jupiter (API de swap e cotação) | execução e preço de execução |
| D-02 | Provedor de RPC da Solana (dois) | envio, confirmação e leitura de saldos |
| D-03 | AWS KMS | geração e guarda das chaves e assinatura |
| D-04 | CoinGecko (plano pago) | dados de mercado reais |
| D-05 | Mecanismo de novo aceite dos termos (intent `261109-termos-lgpd`) | aceite dos termos do REAL |
