# Regras de negócio — U2 `u2-retention`

Conversa: português (Brasil). Fontes: `inception/user-stories/stories.md` (US1.4, US3.1, US3.2), `inception/requirements-analysis/requirements.md` (FR3.1 a FR3.4, NFR1, NFR6, NFR7), `inception/contract-design/contract-summary.md` (J5, J6), `entities.md` e `functional-design-questions.md` (Q1 a Q10).

## Regras

```yaml
rules:
  - id: BR2.1
    statement: Um evento de auditoria guarda a carteira só mascarada e com o código HMAC, nunca o endereço completo
    category: policy
    applies_to: AuditEvent
    trigger: publicação de qualquer evento que envolva uma carteira
    logic: >
      O chamador entrega a carteira por um campo próprio do comando de auditoria. O publicador grava no actor a forma
      mascarada e no walletHmac o HMAC-SHA-256 do endereço com a chave da configuração; o endereço completo não chega a
      details, ao actor nem à saída da trilha (outbox). Vale para login aceito e recusado do convidado e do
      administrador, criação de conta e aceite de convite.
    violation: o publicador recusa o evento e nada é gravado
    source: FR3.3; AC3.2.1; Q1; Q2
  - id: BR2.2
    statement: A mesma carteira gera o mesmo código HMAC em todos os eventos
    category: calculation
    applies_to: WalletReference
    trigger: cálculo do walletHmac
    logic: SE dois eventos envolvem o mesmo endereço e a chave não mudou ENTÃO o walletHmac é igual
    violation: não se aplica (determinístico)
    source: FR3.3; AC3.2.2
  - id: BR2.3
    statement: Nenhum valor de details pode ter a forma de um endereço Base58 completo
    category: validation
    applies_to: AuditEvent
    trigger: validação do evento antes de gravar
    logic: SE algum valor de details contém uma sequência Base58 de 32 a 44 caracteres ENTÃO o evento é recusado com o campo e o motivo, sem o valor
    violation: o publicador recusa o evento, registra o campo e o motivo no log (sem o valor) e o chamador recebe o erro
    source: FR3.3; NFR1; Q2
  - id: BR2.4
    statement: A chave do HMAC é obrigatória e tem pelo menos 32 bytes
    category: constraint
    applies_to: WalletReference
    trigger: subida da API e do worker
    logic: SE a chave falta ou tem menos de 32 bytes ENTÃO o processo não sobe e diz qual configuração falta, sem mostrar valor
    violation: o processo não sobe
    source: NFR6; Q3
  - id: BR2.5
    statement: Um desafio de login é apagado 24 horas depois de expirar, em qualquer estado
    category: policy
    applies_to: LoginChallenge
    trigger: expurgo diário de desafios
    logic: SE expiresAt é anterior ou igual ao instante atual menos 24 horas (relógio injetável) ENTÃO o desafio é apagado; um desafio mais recente fica
    violation: não se aplica
    source: FR3.1; AC3.1.1; Q4
  - id: BR2.6
    statement: Uma sessão terminada há 30 dias ou mais é apagada junto com os digests dela; uma sessão com fim no futuro nunca é apagada
    category: policy
    applies_to: UserSession
    trigger: expurgo diário de sessões
    logic: >
      O fim da sessão é revokedAt quando ela foi revogada; senão, o menor entre refreshExpiresAt e absoluteExpiresAt.
      SE o fim é anterior ou igual ao instante atual menos 30 dias ENTÃO os digests usados da sessão são apagados e,
      depois, a sessão, no mesmo lote. SE o fim está no futuro ENTÃO a sessão fica, qualquer que seja o estado gravado.
      Vale para sessões de convidado e de administrador.
    violation: não se aplica
    source: FR3.2; AC3.1.2; Q5
  - id: BR2.7
    statement: Cada expurgo novo roda uma vez por dia, em lotes, uma só vez entre as instâncias
    category: policy
    applies_to: PurgeRun
    trigger: horário diário (desafios 03:20 UTC; sessões 03:40 UTC)
    logic: >
      O expurgo apaga em lotes de até 1000 até um lote voltar menor; cada lote é uma transação; uma falha mantém os
      lotes já feitos e é repetida no dia seguinte. Ao fim, publica um evento pelo system com a contagem
      (LOGIN_CHALLENGES_PURGED ou SESSIONS_PURGED); o log traz só totais.
    violation: falha registrada no log e na saúde (BR2.8)
    source: NFR7; Q6
  - id: BR2.8
    statement: A saúde de um expurgo fica DEGRADED quando a última execução bem-sucedida tem mais de 48 horas
    category: policy
    applies_to: PurgeRun
    trigger: consulta da saúde
    logic: SE não houve sucesso nas últimas 48 horas e a subida foi há mais de 48 horas ENTÃO DEGRADED; SENÃO UP
    violation: não se aplica
    source: NFR7; AC3.1.3; Q6
  - id: BR2.9
    statement: O catálogo de retenção do Privacy é a fonte da tabela da política
    category: policy
    applies_to: RetentionRule
    trigger: montagem da resposta dos documentos legais (porta J5)
    logic: O catálogo implementa a porta de retenção do Identity; a tabela publicada lista as linhas do catálogo na ordem dele
    violation: não se aplica
    source: FR3.4; J5; Q7
  - id: BR2.10
    statement: Cada linha do catálogo tem quem aplica o prazo declarado
    category: validation
    applies_to: RetentionRule
    trigger: suíte de testes
    logic: >
      SE a linha é PURGE ENTÃO o expurgo citado existe e a declaração dele (prazo e âncora) é igual à da linha; SE é
      GUARD ENTÃO o prazo é "enquanto a conta existir"; SE é PLANNED ENTÃO a linha está na lista de pendências. Qualquer
      diferença faz o teste falhar apontando a linha e os dois prazos.
    violation: o teste falha e aponta a linha divergente
    source: FR3.4; AC1.4.1; AC1.4.2; Q7
  - id: BR2.11
    statement: A tabela de retenção de toda versão não legacy do catálogo de termos é igual ao catálogo de retenção
    category: validation
    applies_to: RetentionRule
    trigger: suíte de testes
    logic: SE alguma linha do retention.tsv de uma versão não legacy difere do catálogo (dado, finalidade, prazo, ação ou ordem) ENTÃO o teste falha apontando a versão e a linha
    violation: o teste falha e aponta a linha divergente
    source: FR3.4; AC1.4.2; Q7
  - id: BR2.12
    statement: Uma linha cujo expurgo chega numa Unit posterior só entra como PLANNED pela lista de pendências
    category: constraint
    applies_to: PendingRetention
    trigger: suíte de testes
    logic: SE uma linha é PLANNED e não está na lista de pendências, ou está na lista mas já tem expurgo ENTÃO o teste falha
    violation: o teste falha
    source: FR3.4; Q8
  - id: BR2.13
    statement: A abertura do beta exige banco novo, a chave HMAC no gerenciador de segredos e as consultas de conferência vazias
    category: policy
    applies_to: AuditEvent
    trigger: checklist de abertura do beta
    logic: O checklist lista o banco novo, a chave e duas consultas que procuram endereços Base58 completos em details e no payload da saída da trilha e precisam voltar vazias
    violation: a abertura não acontece
    source: FR3.3; AC3.2.3; Q9
  - id: BR2.14
    statement: Nenhum evento antigo da trilha é reescrito
    category: constraint
    applies_to: AuditEvent
    trigger: migração da Unit
    logic: A migração só acrescenta a coluna do HMAC; os dados de desenvolvimento e teste são descartados antes da abertura
    violation: não se aplica
    source: FR3.3; Q10
  - id: BR2.15
    statement: Um login recusado com endereço inválido não leva o texto recebido para a auditoria
    category: validation
    applies_to: AuditEvent
    trigger: recusa de login (convidado ou administrador)
    logic: >
      SE o endereço recebido é uma carteira válida ENTÃO ele vai pelo campo próprio (BR2.1) e o evento leva a máscara e o
      HMAC. SENÃO o actor é "anonymous", não há walletHmac (o HMAC nunca é calculado sobre texto que não é carteira) e
      details leva só addressFormat=INVALID, nunca o texto recebido. Como nenhum chamador põe mais o texto recebido em
      details, a recusa de BR2.3 não acontece no fluxo normal; se acontecer, é defeito, coberto pelos testes de recusa de
      login, e não um caminho de produção.
    violation: o evento é gravado sem o texto recebido; a resposta do login continua a recusa de sempre
    source: FR3.3; NFR1; revisão 1
  - id: BR2.16
    statement: O último sucesso de cada expurgo novo fica no banco e a execução perdida é recuperada na subida
    category: policy
    applies_to: PurgeRun
    trigger: fim de cada execução e subida da API
    logic: >
      Cada execução grava, numa tabela do Identity com uma linha por expurgo, o início, o resultado, o último sucesso e o
      corte usado. O indicador de saúde de qualquer instância lê essa linha, então todas as instâncias mostram o mesmo
      estado. Na subida, quando a API fica pronta, cada expurgo novo sem linha, sem sucesso ou com último sucesso há mais
      de 24 horas roda na hora, sempre pela mesma trava, como a recuperação dos jobs da auditoria.
    violation: não se aplica
    source: NFR7; AC3.1.3; revisão 1
  - id: BR2.17
    statement: Cada expurgo declara o próprio prazo por uma interface compartilhada, e o Privacy só lê essas declarações
    category: constraint
    applies_to: PurgeDeclaration
    trigger: montagem do contexto da aplicação e suíte de testes
    logic: >
      A interface fica no pacote compartilhado (shared), não no Privacy nem no módulo dono do expurgo: cada módulo
      registra uma declaração (purgeId, prazo lido da própria configuração, âncora), e o Privacy recebe a lista de
      declarações. Nenhum módulo passa a depender do Privacy, e o Privacy não depende dos módulos que expurgam: não há
      ciclo. A declaração existe no contexto da API mesmo quando o expurgo roda no worker.
    violation: o teste de conformidade falha apontando o expurgo sem declaração
    source: FR3.4; AC1.4.1; revisão 1
  - id: BR2.18
    statement: A cópia da saída da trilha para a trilha leva o walletHmac para a coluna própria
    category: calculation
    applies_to: AuditEvent
    trigger: entrega de um evento da saída para a trilha
    logic: SE o payload tem walletHmac ENTÃO a coluna recebe o valor; SENÃO a coluna fica nula (eventos antigos e eventos sem carteira)
    violation: não se aplica
    source: FR3.3; revisão 1
  - id: BR2.19
    statement: A chave do HMAC não tem rotação no beta
    category: policy
    applies_to: WalletReference
    trigger: operação do beta
    logic: >
      A chave fica a mesma durante o beta. Trocar a chave quebra a correlação entre eventos anteriores e posteriores
      (BR2.2) e só acontece por incidente (vazamento), registrado no runbook. O tratamento do walletHmac e do actor
      mascarado na anonimização fica para a u5-anonymization decidir, como pendência registrada.
    violation: não se aplica
    source: NFR6; revisão 1
```

## Resumo

| ID | Regra | Categoria |
|---|---|---|
| BR2.1 | Auditoria com carteira só mascarada e com HMAC | policy |
| BR2.2 | Mesmo HMAC para a mesma carteira | calculation |
| BR2.3 | `details` sem endereço completo | validation |
| BR2.4 | Chave HMAC obrigatória, com 32 bytes ou mais | constraint |
| BR2.5 | Desafio apagado 24 h depois de expirar | policy |
| BR2.6 | Sessão e digests apagados 30 dias depois do fim | policy |
| BR2.7 | Expurgo diário, em lotes, uma só vez | policy |
| BR2.8 | Saúde `DEGRADED` depois de 48 h sem sucesso | policy |
| BR2.9 | Catálogo do Privacy como fonte da tabela | policy |
| BR2.10 | Cada linha com quem aplica o prazo | validation |
| BR2.11 | `retention.tsv` igual ao catálogo | validation |
| BR2.12 | `PLANNED` só pela lista de pendências | constraint |
| BR2.13 | Checklist de abertura | policy |
| BR2.14 | Trilha antiga sem reescrita | constraint |
| BR2.15 | Login recusado com endereço inválido sem o texto recebido | validation |
| BR2.16 | Último sucesso no banco e recuperação na subida | policy |
| BR2.17 | Declaração dos expurgos por interface compartilhada, sem ciclo | constraint |
| BR2.18 | `walletHmac` copiado do payload para a coluna | calculation |
| BR2.19 | Chave sem rotação no beta; anonimização decide o HMAC | policy |
