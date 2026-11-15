# Perguntas de Viabilidade e Restrições — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`.

## Q1. [Arquitetura] Com o que o Trading REAL precisa se integrar?

A. Módulos existentes (bots e decisão, barreira de risco, ledger e P&L, identidade, administração e painel), a API de swap da Jupiter, um provedor de RPC da Solana e um KMS
B. Só os módulos existentes e a Jupiter
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] Como testar sem arriscar fundos, se a API de swap da Jupiter só funciona na mainnet?

A Jupiter roteia por pools reais da mainnet; na devnet não há a mesma liquidez nem a mesma API.

A. Devnet para carteira do bot, KMS, assinatura, saque e reconciliação; um validador local com contas da mainnet clonadas para os swaps da Jupiter; depois a mainnet com valores mínimos só na carteira do fundador
B. Só devnet, com swaps simulados
C. Direto na mainnet com valores mínimos
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Segurança] Como as chaves das carteiras do bot são geradas e guardadas?

O projeto proíbe código de produção que gere chave secreta ou frase mnemônica.

A. A chave nasce e fica dentro do KMS, não exportável; o KMS assina (Ed25519) e o código de produção nunca vê nem gera a chave; o suporte a Ed25519 do KMS escolhido é verificado antes do desenho
B. A aplicação gera a chave e a guarda cifrada (criptografia de envelope)
C. Um HSM dedicado
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Infraestrutura] Qual KMS usar, já que ainda não há conta de nuvem?

A. AWS KMS numa conta AWS nova, alinhada ao destino AWS do PRD; no ambiente local e nos testes, uma implementação simulada atrás da mesma interface
B. HashiCorp Vault hospedado por conta própria
C. Google Cloud KMS
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Arquitetura] Como montar e assinar transações, se o projeto proíbe o SDK Solana no código de produção?

A. A Jupiter devolve a transação de swap já montada; a produção só assina a mensagem pelo KMS e serializa com código próprio mínimo (como já faz com Ed25519 nativo e Base58 próprio); a regra continua valendo
B. Permitir um SDK Solana Java na produção, mudando a regra
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Produto] Qual é o teto da carteira do bot por usuário?

O teto é a perda máxima por usuário. Ele só pode ser baixado pelo administrador a qualquer momento; subir exige nova liberação com a quarentena de 24 horas.

A. Fundador: 2 SOL; convidados: 0,5 SOL por carteira
B. Fundador: 5 SOL; convidados: 1 SOL
C. Um valor em dólares, convertido na hora do depósito
D. Not yet defined
X. Other (please specify)

[Answer]: X. 10 SOL para mim e para o convidado com teto maximo, configuravel no perfil do convidado

## Q7. [Produto] Quais limites padrão valem por ordem e por dia?

A. Por ordem: até 10% do teto; perda máxima no dia: 20% do teto (ao atingir, o bot pausa até o dia seguinte); no máximo 20 ordens por dia por bot
B. Por ordem: até 25% do teto; perda diária de 50%
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Arquitetura] De onde vêm os dados de mercado do REAL?

Hoje o scanner e os bots usam só o adaptador simulado; operar REAL com sinais simulados não faz sentido.

A. Nesta intent entra uma fonte real mínima (CoinGecko paga, com atribuição) atrás da interface de fonte de mercado existente; o preço de execução vem sempre da cotação da Jupiter no momento da ordem; o REAL não liga com fonte simulada
B. Uma intent separada para dados reais, antes desta
C. Usar só o preço da Jupiter também para os sinais
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Jurídico] Quais exigências regulatórias e de conformidade valem?

A. Posição do fundador de não custódia; parecer escrito recomendado antes dos convidados; termos de uso atualizados para o REAL (risco de perda total do valor depositado, sem promessa de lucro) com novo aceite antes de ativar a carteira do bot; registros fiscais exportáveis pelo usuário
B. Parecer escrito obrigatório antes de qualquer mainnet
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Infraestrutura] Qual provedor de RPC da Solana?

A. Um provedor gerenciado num plano de entrada para devnet e mainnet, com um segundo provedor como reserva; o RPC público só como último recurso
B. Só o RPC público
C. Um nó próprio
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Negócio] Qual é o orçamento mensal do piloto?

A. Até US$ 100 por mês para KMS, RPC e dados de mercado durante o piloto, revisado antes de abrir para convidados
B. Nenhum gasto
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q12. [Negócio] Há prazo ou bloqueio organizacional?

A. Sem data fixa; estimativa de 3 a 6 semanas de trabalho técnico; nenhum bloqueio além da disponibilidade do fundador
B. Data fixa de lançamento
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q13. [Equipe] Que habilidades a construção exige?

A. As que o fundador já usa (Java/Spring e TypeScript/Next.js); não há programa on-chain, então Rust não é necessário; o conhecimento novo é o formato de transação da Solana e a operação do KMS
B. Contratar um especialista em Solana
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
