# Plano e perguntas das histórias de usuário — MemeTrade Analytics

## Plano proposto

- **Formato:** "Como [persona], quero [ação], para que [benefício]", com critérios Dado/Quando/Então (3 a 6 por história), seguindo INVEST.
- **Prioridade:** MoSCoW por história, alinhada à prioridade dos requisitos (Must = primeiro beta).
- **Identificadores:** `US{grupo}.{seq}` e `AC{grupo}.{seq}.{n}`, rastreados até FR/NFR de `../requirements-analysis/requirements.md`.
- **Colaboração:** depois do rascunho, o designer, o desenvolvedor e o engenheiro de qualidade revisam de forma independente.

## Q1. [Produto] Quais personas usamos?
Os requisitos têm dois papéis: usuário beta e administrador.

A. Duas personas: trader convidado (principal) e administrador do beta, que é você (recomendado)
B. Três personas: trader convidado experiente, trader convidado iniciante e administrador
X. Other (please specify)

[Answer]: B. Três personas

## Q2. [Produto] Como organizamos as histórias?

A. Por etapa da jornada do convidado (acesso, descoberta, avaliação, bot PAPER, P&L, feedback), mais um grupo de administração (recomendado)
B. Por módulo técnico (identidade, scanner, risco, ledger...)
C. Por persona
X. Other (please specify)

[Answer]: A. Por jornada

## Q3. [Produto] Qual o tamanho de cada história?

A. Pequenas: cabem em 1 a 3 dias de trabalho, cada uma com 3 a 6 critérios (recomendado)
B. Maiores, no nível de épico, detalhadas depois
X. Other (please specify)

[Answer]: A. Pequenas

## Q4. [Produto] Como tratamos o discovery (G0, entrevistas, inventário de dados) e a prova de conceito da delegação?
Esses itens não são funcionalidades para o usuário.

A. Registrar como itens técnicos de investigação (spikes), separados das histórias, com critérios de pronto, e levá-los ao planejamento de entrega (recomendado)
B. Escrevê-los também como histórias de usuário
X. Other (please specify)

[Answer]: A. Spikes separados

## Q5. [Produto] Como funciona a retomada depois que o bot atinge a perda diária máxima?
A revisão dos requisitos apontou uma contradição: o texto permite retomar "por nova ação da pessoa", mas o limite continua estourado no mesmo dia.

A. O limite vale por bot e reinicia à meia-noite UTC; antes disso, a pessoa só pode retomar se elevar o limite, passando por nova revisão dos limites (recomendado)
B. O limite vale por bot e só reinicia à meia-noite UTC; não há retomada manual no mesmo dia
C. O limite vale por convidado (soma de todos os bots) e reinicia à meia-noite UTC
X. Other (please specify)

[Answer]: A. Por bot, retomada só elevando o limite com nova revisão

## Q6. [Produto] O que acontece ao encerrar um bot quando não há preço confiável para fechar uma posição?
Pode acontecer com preço `unknown`, desatualizado ou sem liquidez.

A. A posição fica em "encerramento pendente" até haver preço confiável, com aviso visível; o bot não faz novas operações (recomendado: o ledger nunca recebe preço inventado)
B. Fecha com a última marcação válida, sinalizada como "preço não confiável" no ledger e no P&L
X. Other (please specify)

[Answer]: A. Encerramento pendente

## Q7. [Produto] Quantas estratégias modelo o beta terá?
As regras exatas de entrada e saída são detalhadas no design funcional; aqui definimos o número e a ideia de cada uma.

A. Duas: "momentum de volume" (entra quando volume e preço sobem juntos, acima da liquidez mínima) e "rompimento de faixa" (entra quando o preço rompe a faixa recente com liquidez estável); ambas saem por alvo, stop ou tempo máximo (recomendado)
B. Uma só: "momentum de volume"
X. Other (please specify)

[Answer]: A. Duas

## Q8. [Qualidade] Como valem as fronteiras dos limites?
Levantado pelo engenheiro de qualidade: sem isso os testes não sabem o resultado esperado no valor exato do limite.

A. Máximos e mínimos são inclusivos (valor igual ao limite é permitido); a perda diária é a exceção: atingir o limite já pausa; "mais de 5 minutos" significa estritamente acima de 5:00 (recomendado)
B. Máximos exclusivos (valor igual ao limite é recusado)
X. Other (please specify)

[Answer]: A. Inclusivas

## Q9. [Produto] O que conta na perda diária e no drawdown do bot?
Levantado pelo desenvolvedor e pelo engenheiro de qualidade: a regra define quando o bot pausa.

A. Perda diária = resultado realizado do dia UTC, já com taxas; drawdown = queda do patrimônio virtual (saldo + posições marcadas a mercado) a partir do maior valor já atingido pelo bot (recomendado)
B. Perda diária também inclui o não realizado (marcação a mercado das posições abertas)
C. Deixar as duas definições para o design funcional
X. Other (please specify)

[Answer]: A. Realizado + pico

## Q10. [Produto] Qual a idade máxima do dado de mercado para o bot criar uma ordem simulada?

A. No máximo 60 segundos, igual à meta de frescor do scanner (recomendado)
B. Um parâmetro do bot, de até 5 minutos
X. Other (please specify)

[Answer]: A. 60 segundos

## Q11. [Produto] Quais bots contam no limite de 3 ativos, e o que vale quando motivos de pausa se acumulam?

A. Só bots ATIVOS contam; retomar um bot pausado conta como ativação (recusada se já houver 3); a meia-noite UTC remove só o motivo "perda diária"; se o bot voltaria e já houver 3 ativos, ele fica pausado com aviso (recomendado)
B. Todo bot fora de rascunho e encerrado conta no limite, inclusive pausados
X. Other (please specify)

[Answer]: A. Só ativos contam

## Q12. [Produto] Um bot pausado ainda pode fechar posições por alvo, stop ou tempo máximo?

A. Não: pausa bloqueia qualquer nova operação; as posições ficam abertas e marcadas a mercado até a pessoa retomar ou encerrar o bot (recomendado: mantém "pausa impede novas operações" sem exceções)
B. Sim: pausa bloqueia entradas, mas as saídas por alvo, stop ou tempo máximo continuam
X. Other (please specify)

[Answer]: A. Pausa bloqueia tudo

## Q13. [Produto] Como ficam "Sair" e "Desconectar carteira", já que a carteira é a identidade da conta?

A. Só "Sair" no beta: encerra a sessão e os bots continuam; não existe desconectar carteira (recomendado)
B. Manter os dois: "Desconectar carteira" = sair e pausar todos os bots
X. Other (please specify)

[Answer]: A. Só Sair

## Q14. [Produto] Em que fuso funciona o filtro de período do P&L?
O limite de perda diária usa o dia UTC.

A. Filtro no fuso do usuário; o limite diário aparece explicitamente em UTC, com o horário de reinício convertido (recomendado)
B. Tudo em UTC, inclusive o filtro do P&L
X. Other (please specify)

[Answer]: A. Fuso do usuário

## Q15. [Produto] O beta tem correção manual de lançamentos no ledger?
O ledger só aceita correção por lançamento compensatório; nenhuma história cria um hoje.

A. Não: o mecanismo de compensação existe e é testado, mas o beta não tem tela nem ação de correção manual (recomendado)
B. Sim: o administrador pode registrar uma compensação auditada
X. Other (please specify)

[Answer]: A. Sem correção manual

## Q16. [Produto] Qual é o canal de feedback do beta?

A. Formulário curto dentro do produto, acessível pelo menu (recomendado)
B. Link para um canal externo (formulário ou mensagem), sem armazenamento no produto
X. Other (please specify)

[Answer]: A. Formulário no produto

## Consolidated Summary Confirmation

- Três personas: trader convidado experiente, trader convidado iniciante (já conhece Solana e carteiras, mas é novo em estratégias automatizadas) e administrador do beta (você).
- Histórias organizadas pela jornada do convidado (acesso, descoberta, avaliação, bot PAPER, P&L, feedback) e um grupo de administração.
- Histórias pequenas, de 1 a 3 dias, com 3 a 6 critérios Dado/Quando/Então cada.
- Discovery (G0, entrevistas, inventário de dados, estimativas) e prova de conceito da delegação como itens técnicos de investigação separados, com critérios de pronto.
- Perda diária máxima vale por bot e reinicia à meia-noite UTC; antes disso o bot só retoma se a pessoa elevar o limite com nova revisão.
- Encerrar um bot sem preço confiável deixa a posição em "encerramento pendente", com aviso e sem novas operações, até haver preço confiável.
- Duas estratégias modelo: "momentum de volume" e "rompimento de faixa", com saída por alvo, stop ou tempo máximo; regras exatas no design funcional.
- Fronteiras dos limites inclusivas; atingir a perda diária já pausa; "mais de 5 minutos" é estritamente acima de 5:00.
- Perda diária = resultado realizado do dia UTC com taxas; drawdown = queda do patrimônio virtual (saldo + posições marcadas) desde o maior valor já atingido.
- Ordem simulada só com dado de mercado de no máximo 60 segundos.
- Só bots ativos contam no limite de 3; retomar conta como ativação; a meia-noite UTC remove só o motivo "perda diária"; com 3 ativos, o bot fica pausado com aviso.
- Pausa bloqueia qualquer nova operação, inclusive saídas; posições ficam abertas e marcadas até retomar ou encerrar.
- Só "Sair" no beta (encerra a sessão, bots continuam); não existe desconectar carteira.
- Filtro de período do P&L no fuso do usuário; limite diário exibido em UTC com reinício convertido.
- Sem correção manual no ledger; o mecanismo de compensação existe e é testado.
- Feedback por formulário curto no produto, acessível pelo menu.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
