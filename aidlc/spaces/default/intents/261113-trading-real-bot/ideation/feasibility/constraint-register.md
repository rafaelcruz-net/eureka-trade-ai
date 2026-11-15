# Registro de restrições — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md` e `feasibility-questions.md` (Q1 a Q13).

## Técnicas

| ID | Restrição | Origem | Status |
|---|---|---|---|
| TR-C01 | REAL e PAPER estritamente separados em fundos, ordens, ledger, posições e P&L | regra do projeto; intent-statement | Confirmada |
| TR-C02 | A chave da carteira do bot nasce e fica no KMS, não exportável; o KMS assina | Q3 | Confirmada; suporte a Ed25519 a verificar |
| TR-C03 | Sem SDK Solana na produção; a transação vem pronta da Jupiter e a produção só assina e serializa | Q5; regra do projeto | Confirmada |
| TR-C04 | Sem programa on-chain próprio nesta intent | intent-statement | Confirmada |
| TR-C05 | A API de swap da Jupiter só opera na mainnet; os swaps são testados num validador local com contas clonadas | Q2 | Confirmada |
| TR-C06 | O REAL não liga com a fonte de mercado simulada; uma fonte real (CoinGecko paga, com atribuição) entra nesta intent | Q8 | Confirmada |
| TR-C07 | O preço de execução vem sempre da cotação da Jupiter no momento da ordem | Q8 | Confirmada |
| TR-C08 | RPC por provedor gerenciado com uma reserva; RPC público só como último recurso | Q10 | Confirmada |
| TR-C09 | Saque da carteira do bot só para a carteira principal verificada do usuário | intent-statement | Confirmada |
| TR-C10 | Valores monetários e quantidades em decimal exato ou unidades base, nunca ponto flutuante | regra do projeto | Confirmada |

## De produto e risco

| ID | Restrição | Origem | Status |
|---|---|---|---|
| TR-C11 | Teto máximo da carteira do bot: 10 SOL, para o fundador e para cada convidado | Q6 | Confirmada |
| TR-C12 | O teto de cada convidado é configurável no perfil do convidado, até o máximo | Q6 | Confirmada |
| TR-C13 | Por ordem: até 10% do teto; perda máxima no dia: 20% do teto, com pausa até o dia seguinte; até 20 ordens por dia por bot | Q7 | Confirmada |
| TR-C14 | Modo padrão: Assinada; voltar a um modo mais restrito é imediato, subir pede consentimento assinado | intent-statement | Confirmada |
| TR-C15 | Liberação: devnet, depois a carteira do fundador, depois até 20 convidados; cada liberação após 24 horas com tudo verde | intent-statement | Confirmada |

## Organizacionais

| ID | Restrição | Origem | Status |
|---|---|---|---|
| TR-C16 | Fundador solo, único revisor | intent-statement | Confirmada |
| TR-C17 | Orçamento de até US$ 100 por mês no piloto | Q11 | Confirmada |
| TR-C18 | Sem data fixa; 3 a 6 semanas estimadas | Q12 | Confirmada |
| TR-C19 | Stack atual (Java/Spring, TypeScript/Next.js); sem Rust | Q13 | Confirmada |
| TR-C20 | Conta AWS nova para o KMS; simulação no ambiente local e nos testes | Q4 | Pendente: conta a criar |

## Regulatórias

| ID | Restrição | Origem | Status |
|---|---|---|---|
| TR-C21 | Posição de não custódia do fundador; parecer escrito recomendado antes dos convidados na mainnet | intent-statement; Q9 | Pendente: parecer |
| TR-C22 | Termos de uso atualizados para o REAL, com novo aceite antes de ativar a carteira do bot; sem promessa de lucro | Q9; regra do projeto | Confirmada |
| TR-C23 | Registros fiscais exportáveis pelo usuário | Q9 | Confirmada |
| TR-C24 | Nenhum fundo real se move sem aprovação explícita do fundador | regra do projeto | Confirmada |
