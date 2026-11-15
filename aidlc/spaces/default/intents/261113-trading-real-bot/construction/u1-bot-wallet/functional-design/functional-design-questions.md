# Perguntas de Functional Design — U1 `u1-bot-wallet`

Conversa: português (Brasil). Base: `inception/units-generation/unit-of-work.md` (U1), `inception/units-generation/unit-of-work-story-map.md` (US1.1 parte do servidor, US1.2, US2.2, US5.1, US8.5), `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md` (FR2, FR3, FR9, FR10, FR11), `inception/domain-design/components.md` (TxSigner, BotWallet, ChainWatcher, Ledger, Audit, Identity), `inception/contract-design/contract-summary.md` (R1, R2, M1 a M3, M8, M9, mensagem de consentimento) e os achados levados das revisões (saque com REAL pausado, SOL de origem desconhecida, saque que falha ou fica ambíguo, nonce, valores ausentes no desafio).

## Q1. [Regra] Quanto vale a reserva mínima para taxas (FR3.5)?

A. 0,03 SOL (30.000.000 lamports), configurável por ambiente: cobre taxas de rede e o aluguel de algumas contas de token criadas nos swaps; fica fora do saldo operável e volta no saque total, menos a taxa do próprio saque
B. 0,01 SOL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Teto] Onde fica o teto máximo de cada usuário ("configurável no perfil do convidado")?

A. Em `RealPreferences.maxCapLamports`, padrão 10 SOL e nunca acima disso; o administrador baixa ou ajusta até 10 SOL (U5); na U1 o valor vem do padrão; o usuário escolhe o próprio teto até esse máximo; na ativação o teto inicial é o do consentimento e o modo inicial é Assinada
B. Um teto único para todos
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Depósito] Como detectar os depósitos?

A. Um trabalho no worker consulta, a cada 15 s, as entradas de cada carteira do bot ativa pela ChainWatcher, só com transações finalizadas; cada depósito é registrado uma vez (chave: assinatura da transação) e lançado no ledger REAL na mesma transação do registro
B. Só quando o usuário abre a tela
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Depósito] SOL vindo de um endereço que não é a carteira principal?

A. Entra no saldo (está na rede e o ledger precisa bater), fica marcado como origem desconhecida (`knownSource=false`, origem só como HMAC), gera alerta para o administrador e aviso para o usuário; nunca é devolvido à origem — sai só pelo saque para a carteira principal
B. Recusar e devolver à origem
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Saque] Saque com o REAL pausado por divergência?

A. Permitido, mas o saldo livre usado é o menor entre o do ledger e o saldo real da rede naquele momento, para nunca sacar o que talvez não exista; o saque gera uma nova reconciliação ao confirmar
B. Bloquear o saque até resolver a divergência
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Saque] O que acontece quando o saque falha ou fica ambíguo?

A. Estados pedido → assinado → enviado → confirmado | falhou | ambíguo. Falhou (blockhash vencido sem a assinatura na rede, ou recusa do TxSigner): a reserva é liberada e o usuário pode pedir de novo. Ambíguo (sem confirmação em 60 s): a reserva fica, a assinatura é conferida na rede até o blockhash vencer, e só então vira confirmado ou falhou; enquanto houver saque ambíguo, um novo saque total é recusado (`ORDER_AMBIGUOUS`), mas um saque parcial do saldo livre restante é permitido
B. Repetir automaticamente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Saque] Regras de valor do saque parcial

A. O saque parcial não pode deixar a carteira do bot com saldo entre zero e o mínimo isento de aluguel da rede, nem invadir a reserva para taxas; o saque total devolve tudo (inclusive a reserva) menos a taxa do próprio saque, e só é aceito sem posição nem ordem em andamento; com posição ou ordem, o "tudo" vira o saldo livre
B. Sem regra de mínimo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Consentimento] Nonces e valores ausentes no desafio

A. Cada nonce usado fica guardado até a validade dele mais um dia, tanto na API quanto no TxSigner (não só o último), e nunca é aceito de novo; no desafio, o valor que não muda entra na mensagem com o valor vigente (em RAISE_MODE vai o teto atual; em RAISE_CAP vai o modo atual; na ativação vão o modo Assinada e o teto escolhido)
B. Guardar só o último nonce
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q9. [Liberação mínima] Como o esqueleto libera a conta do fundador?

A. Uma `RealRelease` gravada só pelo perfil de teste e local (semente do fundador de teste), com `effectiveAt` = registro + 24 h e um relógio injetável nos testes; nenhuma rota de liberação na U1 (ela vem na U5); a ativação confere liberação vigente (efetiva e não revogada) para a rede do ambiente
B. Liberar sem quarentena no esqueleto
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Pausa] Quem pausa e quem retoma na U1?

A. Pausa por motivo (`USER`, `DIVERGENCE`, `DAILY_LOSS`, `KILL_SWITCH`, `ADMIN`, `UPSTREAM`); o usuário só retoma a pausa `USER`; as outras são retomadas por quem as causou (reconciliação, virada do dia, administrador); a pausa nunca bloqueia saque nem leitura; rebaixar teto e modo sempre é possível
B. Uma pausa só, sem motivo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q11. [Auditoria] Quais eventos a U1 grava?

A. Ativação (pedida, criada, recusada), consentimento (aceito, recusado com motivo), depósito detectado (com origem conhecida ou não), saque (cada mudança de estado), mudança de teto, mudança de modo, pausa e retomada, assinatura e recusa no TxSigner (com o código do motivo). Campos tipados: `txSignature`, `mint`, `botWalletAddress`; a carteira principal só como HMAC; logs mascaram endereço e assinatura
B. Só ativação e saque
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Reserva para taxas de 0,03 SOL, configurável, devolvida no saque total menos a taxa [Q1].
- Teto máximo em RealPreferences, padrão 10 SOL; teto do usuário até o máximo; ativação em Assinada com o teto do consentimento [Q2].
- Depósitos detectados a cada 15 s, só finalizados, uma vez por assinatura, lançados no ledger na mesma transação [Q3].
- Origem desconhecida entra no saldo, marcada e com alerta; sai só pelo saque para a principal [Q4].
- Saque com divergência usa o menor saldo entre ledger e rede [Q5].
- Saque falho libera a reserva; ambíguo segura a reserva até conferir; saque total recusado enquanto houver ambíguo [Q6].
- Saque parcial respeita o mínimo isento de aluguel e a reserva; total só sem posição nem ordem [Q7].
- Nonces guardados até a validade mais um dia na API e no TxSigner; valor que não muda entra com o vigente [Q8].
- Liberação do fundador só pelo perfil de teste, com 24 h e relógio injetável; sem rota na U1 [Q9].
- Pausa com motivo; usuário retoma só a pausa USER; pausa nunca bloqueia saque [Q10].
- Eventos de auditoria da U1 com campos tipados e carteira principal em HMAC [Q11].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
