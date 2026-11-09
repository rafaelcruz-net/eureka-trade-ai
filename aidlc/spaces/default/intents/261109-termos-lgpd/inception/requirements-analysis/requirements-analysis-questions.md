# Perguntas da Análise de Requisitos — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/scope-definition/scope-document.md`, a base de conhecimento do código (`aidlc/spaces/default/codekb/eureka-trader-ai/`, em especial a tabela de dados pessoais de `architecture.md`) e `inception/practices-discovery/team-practices.md`.

O que a varredura do código mostrou e que pede decisão:
- Sem expurgo: `login_challenge` (endereço completo e mensagem assinada), `user_session` e `session_used_refresh_digest`.
- Endereço completo da carteira em `audit_event.details` (12 meses) e no `audit_outbox` (7 dias). A trilha recusa UPDATE para todos.
- `wallet.address` é obrigatório, único e validado como Base58. A conta (`app_user`) não pode ser apagada, porque o ledger e outras tabelas sem edição apontam para ela.
- O aceite dos termos só é conferido na criação da conta; não há novo aceite nem política de privacidade.
- Texto livre que pode conter dado pessoal: `feedback_entry.text` (180 dias) e `bot.name` (sem prazo).

## Q1. Que prazo dar aos dados que hoje não têm expurgo?

A) Três prazos:
- desafios de login apagados 24 horas depois de expirarem;
- sessões e digests de renovação apagados 30 dias depois do fim ou da revogação;
- registros de idempotência continuam em 24 horas.

(recomendado)

B) Prazos menores (diga quais)
X) Other (please specify)

[Answer]: A

## Q2. E o endereço completo da carteira na trilha de auditoria?

A) Minimizar daqui em diante. Os eventos novos guardam só o endereço mascarado e um código de correlação calculado com chave secreta (HMAC), que permite achar os eventos de uma carteira sem guardar o endereço. Os eventos antigos saem pelo expurgo de 12 meses, e a política informa isso (recomendado)
B) Manter o endereço completo por 12 meses e declarar na política
X) Other (please specify)

[Answer]: A

## Q3. Como anonimizar a conta?

A) Na anonimização, numa só transação auditada:
- `app_user` vai para o estado novo `ANONYMIZED`;
- o endereço e o provedor da carteira são apagados (uma migração passa a permitir endereço vazio só nesse estado);
- as sessões são revogadas;
- o feedback da pessoa é apagado;
- os nomes dos bots viram "Bot anonimizado";
- os bots ativos são parados.

O ledger, as ordens, as recusas e o aceite dos termos ficam, ligados só ao identificador interno. A pessoa não consegue mais entrar com aquela carteira; se voltar com convite novo, é uma conta nova (recomendado)

B) Só desativar a conta, sem apagar a carteira
X) Other (please specify)

[Answer]: A

## Q4. Por quanto tempo ficam os dados da conta e o histórico PAPER?

A) Enquanto a conta existir. Depois da anonimização, o histórico PAPER fica sem vínculo com a pessoa. A política diz isso (recomendado)
B) Apagar o histórico PAPER depois de um prazo (diga qual)
X) Other (please specify)

[Answer]: A

## Q5. Como funciona o novo aceite quando a versão muda?

A) Com versão nova publicada, a API responde `403` com o código `TERMS_REACCEPTANCE_REQUIRED` a toda rota do convidado, menos estas: leitura dos termos e da política, aceite, Conta, pedido de titular e sair. O app leva à tela de novo aceite, com o resumo do que mudou. Os bots PAPER que já rodam continuam (são simulados), mas nada novo é criado ou alterado até o aceite (recomendado)
B) Pausar também os bots até o aceite
X) Other (please specify)

[Answer]: A

## Q6. Como são os pedidos de titular?

A) Assim:
- tipos: Acesso, Correção, Exclusão e Outro;
- estados: Recebido, Em andamento, Concluído e Recusado (com motivo);
- prazo de 15 dias corridos a partir do recebimento;
- um pedido aberto por tipo e por pessoa;
- texto de até 1000 caracteres, que nunca vai para log;
- cada mudança de estado gera evento de auditoria;
- a resposta é um texto escrito pelo administrador, sem exportação automática;
- os pedidos ficam guardados 12 meses depois de concluídos, para prestação de contas.

(recomendado)

B) Prazo de guarda maior para os pedidos (diga qual)
X) Other (please specify)

[Answer]: A

## Q7. E os pontos que ficaram dos rascunhos de telas?

A) Cinco decisões:
- confirmar "não usamos rastreamento", porque o produto só guarda no navegador o necessário: token em memória e aceite na sessão da aba;
- manter a versão aceita no rodapé quando há login;
- manter a caixa do aviso de risco no convite, que já existe;
- o ponto de entrada do teclado da T1 vale para todas as telas;
- o filtro e o alerta de prazo da T6 ficam no item Should (C7).

(recomendado)

B) Tirar a versão aceita do rodapé
X) Other (please specify)

[Answer]: A

## Q8. Quem escreve o texto dos termos v2 e da política?

A) Eu redijo, a partir do que o sistema faz de verdade (dados, prazos, bases legais indicadas no parecer, direitos). A assessoria jurídica revisa antes da publicação. A versão do beta só é publicada com a revisão feita e os campos de implantação preenchidos: local de armazenamento, transferência internacional e e-mail do encarregado (recomendado)
B) A assessoria escreve; eu só publico o texto que ela entregar
X) Other (please specify)

[Answer]: A

# Perguntas de acompanhamento (revisão de produto, 2026-11-09)

## Q9. Eventos de auditoria antigos com o endereço completo e a anonimização

A trilha não aceita edição, e os eventos antigos guardam o endereço completo. Mas ainda não existe nenhum convidado real: o beta não abriu.

A) A minimização de FR3.3 entra antes de abrir o beta. Nenhum convidado real terá evento com o endereço completo. Antes da abertura, os dados de desenvolvimento e teste são descartados (ambiente novo). O critério de FR6.2 vale para eventos gravados depois de FR3.3. A trilha continua sem nenhuma edição (recomendado)
B) Permitir que a anonimização reescreva só o campo `details` dos eventos da pessoa, com um papel de banco específico e um evento que registra a reescrita
X) Other (please specify)

[Answer]: A

## Q10. Pedido de titular sem login

A) Não há formulário sem login. O botão "Fazer um pedido" sem sessão leva à entrada. Quem não consegue entrar (conta anonimizada, carteira perdida) usa o e-mail do encarregado publicado na política. O administrador registra esse pedido no app do administrador, com o mesmo prazo e a mesma auditoria (recomendado)
B) Criar um formulário público sem login, com verificação por assinatura da carteira e limite de tentativas
X) Other (please specify)

[Answer]: A

## Q11. Como a pessoa recebe a resposta, e o que se entrega em Acesso e Correção

A) Assim:
- a pessoa vê na Conta a resposta escrita e, quando houver, o motivo da recusa;
- no Acesso, o sistema monta um resumo dos dados da pessoa (carteira mascarada, datas da conta, versões aceitas, quantidade de bots e de ordens, feedbacks enviados, pedidos), que o administrador confere e libera junto da resposta;
- na Correção, o que a pessoa pode mudar sozinha (nome dos bots) é indicado na resposta;
- a carteira não se corrige, porque é a identidade da conta: a alternativa é a exclusão.

(recomendado)

B) Só a resposta escrita, sem resumo automático
X) Other (please specify)

[Answer]: A

## Q12. O que acontece quando o prazo de 15 dias vence?

A) Faz parte do Must: o pedido vencido gera um evento de auditoria (`DATA_REQUEST_OVERDUE`), conta na métrica de vencidos e deixa a saúde dos pedidos em `DEGRADED`. O alerta visual da lista continua Should (recomendado)
B) Só o alerta visual (Should)
X) Other (please specify)

[Answer]: A

## Q13. Como registrar a revisão jurídica?

A) No próprio catálogo versionado: o arquivo de cada versão traz a data da revisão e quem revisou. Sem esses campos, a versão não pode ser ativada; a API sobe com a versão anterior e um aviso no log (recomendado)
B) Numa variável de configuração da implantação
X) Other (please specify)

[Answer]: A

## Q14. E se a versão aceita não for a vigente?

A) A API responde `409` com o código `TERMS_VERSION_OUTDATED` e a versão vigente. O app recarrega os documentos e pede o aceite de novo. O aceite repetido de uma versão já aceita devolve o mesmo resultado, sem nova gravação (recomendado)
B) Aceitar a versão enviada mesmo superada
X) Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation
Does this all look correct before I generate the artifact?

- Expurgos novos: desafios de login 24 h após expirar; sessões e digests de renovação 30 dias após o fim ou a revogação; idempotência segue 24 h.
- Auditoria: eventos novos com carteira mascarada e código de correlação HMAC; os antigos saem no expurgo de 12 meses, informado na política.
- Anonimização em uma transação auditada: estado `ANONYMIZED`, carteira e provedor apagados (migração), sessões revogadas, feedback apagado, bots renomeados e parados; ledger, ordens, recusas e aceite ficam com o identificador interno; volta só por convite novo.
- Histórico PAPER enquanto a conta existir; depois da anonimização, sem vínculo.
- Novo aceite: `403 TERMS_REACCEPTANCE_REQUIRED` fora das rotas liberadas (documentos, aceite, Conta, pedido de titular, sair); bots em execução continuam, nada novo até o aceite.
- Pedidos de titular: 4 tipos, 4 estados, 15 dias corridos, um aberto por tipo, texto até 1000 sem log, auditoria por mudança, resposta escrita, guarda de 12 meses após concluído.
- Rascunhos: "não usamos rastreamento" confirmado; versão aceita no rodapé; caixa de risco no convite; padrão de teclado da T1 em todas; filtro e alerta da T6 como Should.
- Textos: redigidos a partir do sistema, revisados pela assessoria; publicação só com revisão e campos de implantação preenchidos.
- Auditoria antiga (Q9): a minimização entra antes de abrir o beta; nenhum convidado real terá o endereço completo na trilha; dados de teste descartados antes da abertura; a trilha segue sem edição; o critério da anonimização vale para eventos gravados depois da minimização.
- Sem login (Q10): sem formulário público; sem sessão, o botão leva à entrada; quem não entra usa o e-mail do encarregado e o administrador registra o pedido, com o mesmo prazo e auditoria.
- Resposta (Q11): a pessoa vê na Conta a resposta e o motivo da recusa; no Acesso, resumo automático dos dados conferido e liberado pelo administrador; na Correção, o nome dos bots a pessoa muda sozinha e a carteira não se corrige (alternativa: exclusão).
- Prazo vencido (Q12): evento `DATA_REQUEST_OVERDUE`, métrica de vencidos e saúde `DEGRADED`, no Must; o alerta visual segue Should.
- Revisão jurídica (Q13): data e responsável no arquivo da versão no catálogo; sem eles, a versão não ativa e a API mantém a anterior com aviso no log.
- Versão superada (Q14): `409 TERMS_VERSION_OUTDATED` com a versão vigente; o app recarrega e pede o aceite; aceite repetido da mesma versão não grava de novo.

- Looks correct
- Request changes

[Answer]: Looks correct
