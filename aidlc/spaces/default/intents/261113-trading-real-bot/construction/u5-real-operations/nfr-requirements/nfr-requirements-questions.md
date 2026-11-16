# Perguntas de NFR Requirements — U5 `u5-real-operations`

Conversa: português (Brasil). Base:
- `construction/u5-real-operations/functional-design/`: `rules.md` BR1.1 a BR7.2, `functional-spec.md` F1 a F10 e S1 a S29;
- `inception/requirements-analysis/requirements.md` (NFR1 a NFR10);
- o padrão de NFR das U3 e U4 (IDs com prefixo, rótulos fechados, alertas no `promtool` e no Terraform);
- `team.md`: testes obrigatórios por risco, entre eles a anonimização sem endereço, o saque sempre disponível e o REAL desligado por padrão.

## Q1. [Desempenho] Quais metas?

A. As metas são p95 e informativas, sem portão na CI:
- **Reconciliação de uma carteira:** até 5 s.
- **Rodada de 15 minutos para 21 carteiras:** até 60 s.
- **Trabalho de portões:** até 10 s, com a leitura da CI.
- **Rotas de leitura do administrador:** até 500 ms (lista paginada de 50 e detalhe).
- **Ligar o kill switch:** a recusa das ordens novas vale na próxima leitura (imediata). O cancelamento das não enviadas termina em até 2 minutos.
B. Sem metas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Segurança] Que proteções?

A. As proteções:
- As rotas R4 exigem o papel `ADMIN` pela entrada administrativa, `Idempotency-Key` e motivo nas mudanças.
- O token do GitHub é só de leitura de Actions, fica num segredo próprio do worker e nunca vai para log.
- A visão do administrador nunca mostra chave nem a carteira principal inteira.
- A anonimização apaga os endereços em todas as tabelas e na trilha, pela função auditada.
- Na rede, o código só lê; a U5 nunca assina.
- Regras ArchUnit para as fronteiras dos módulos e a rede só no adaptador do GitHub e no RPC que já existe.
B. Só o papel ADMIN
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Confiabilidade] Como sobreviver a falhas?

A. As regras:
- Liberação, kill switch, divergência e comandos pendentes usam compare-and-set e chave de idempotência.
- Os trabalhos usam ShedLock e são idempotentes: portões, reconciliação, cancelamento do kill switch e reenvio ao TxSigner.
- A conferência de restauração bloqueia ordens novas até terminar.
- A falha da CI ou do RPC deixa o portão vermelho ou a reconciliação `UNAVAILABLE`, nunca libera nem abre divergência.
- O saque nunca depende da U5, e o seu limite com divergência é a única regra que a U5 acrescenta a ele.
B. Recuperação manual
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Observabilidade] Que métricas e alertas?

A. Métricas, sem usuário, endereço ou mint nos rótulos:
- liberações por estado;
- bloqueios por motivo;
- reconciliações por resultado;
- divergências abertas;
- carteiras `DEFERRED` há mais de 2 horas;
- kill switch ativo;
- pausa de restauração ativa;
- comandos pendentes ao TxSigner por tipo e idade;
- batimento de cada trabalho.

Alertas:
- divergência aberta (crítico);
- pausa de restauração (crítico);
- kill switch ligado (alto, para não esquecer ligado);
- reconciliação adiada há mais de 2 horas (alto);
- comando pendente ao TxSigner há mais de 15 minutos (alto);
- liberação bloqueada há mais de 1 hora (médio);
- trabalho parado (crítico).

Tudo no padrão da U3, com regras do `promtool`, alarmes no Terraform e teste de paridade.
B. Só os logs
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Testes] Como provar?

A. Testes antes do código para:
- a reconciliação (cenários com resultado conhecido, com e sem trânsito, depósito não lido e taxa da carteira principal);
- a compensação e o saque limitado;
- a anonimização: a função de redação e nenhum endereço sobrando, o teste obrigatório do `team.md`;
- os portões e o "fundador primeiro".

Integração com PostgreSQL real para concorrência (dois pedidos de liberação, kill switch ligando e desligando) e restauração. Ponta a ponta: um spec novo no `verify.sh`, com uma divergência provocada no validador local por transferência direta, a pausa, a compensação e a despausa. Piso de 80%.
B. Testes depois do código
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Tecnologia] Onde fica cada parte?

A. A divisão:
- **`botwallet`:** liberação, reconciliação, divergência, saque limitado, restauração e comandos pendentes, junto do que a U1 já tem.
- **`administration`:** kill switch REAL e rotas R4, com o adaptador do GitHub em `administration.infra.github`.
- **`privacy`:** anonimização, que chama a M1.

Os trabalhos rodam no worker; as rotas e o expurgo, na API. Nenhuma biblioteca nova.
B. Um módulo novo `realops`
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Reconciliação em até 5 s por carteira e 60 s por rodada; portões em até 10 s; telas do administrador em até 500 ms; kill switch imediato, com o cancelamento em até 2 min [Q1].
- Rotas só do ADMIN com idempotência e motivo; token do GitHub só de leitura; nenhuma chave nem carteira principal inteira; anonimização sem endereço [Q2].
- Compare-and-set, idempotência e trabalhos com ShedLock; falhas nunca liberam nem abrem divergência; o saque não depende da U5 [Q3].
- Métricas e alertas de divergência, restauração, kill switch, reconciliação adiada, comando pendente, liberação bloqueada e trabalho parado [Q4].
- Testes antes do código para reconciliação, compensação, saque limitado, anonimização e portões; concorrência com PostgreSQL real; ponta a ponta da divergência no validador local [Q5].
- Partes em `botwallet`, `administration` (com o GitHub) e `privacy`; sem biblioteca nova [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
