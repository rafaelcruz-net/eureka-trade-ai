**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-11-09T12:53:50Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR6.2 e FR3.3 | FR6.2 exige que, depois da anonimização, o endereço completo não apareça em nenhuma tabela nem log, mas FR3.3 deixa os eventos de auditoria antigos, que guardam o endereço completo, até o expurgo de 12 meses. O critério de FR6.2 e o teste obrigatório do time ("a anonimização não deixa o endereço da carteira em nenhuma tabela") ficam impossíveis de passar para contas com eventos antigos. | Resolver a contradição: ou a anonimização mascara o endereço nos eventos de auditoria antigos da pessoa (e diz como isso convive com a trilha sem edição), ou a exceção temporária entra no critério de FR6.2 e na política com o prazo máximo declarado. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR2.4 e FR5 | FR2.4 promete uma "entrada sem login" para fazer pedidos, mas nenhum requisito define esse caminho: quem pode usá-lo, como a pessoa é identificada, o que se grava, proteção contra abuso e como isso respeita o isolamento por tenant. Pessoas com conta anonimizada, sessão revogada ou sem acesso ao login ficam sem canal definido. | Acrescentar requisitos (ou remover o ponto de FR2.4) para o pedido sem login: campos, verificação de identidade, limite de taxa, prazo, auditoria e critério de teste. | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR5.2 a FR5.4 | A resposta ao titular não é fechada: FR5.4 cria o campo de resposta, mas FR5.2 não diz que a pessoa vê a resposta nem o motivo da recusa, e nada define o que é entregue em um pedido de Acesso ou de Correção (quais dados, em que formato, quem corrige e como). Sem isso o prazo de 15 dias pode ser cumprido só no papel. | Definir a entrega da resposta ao titular (onde e quando ele a vê, inclusive o motivo da recusa) e o conteúdo mínimo do atendimento de Acesso e de Correção, cada um com critério de aceite testável. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR5.3 e FR7.1 | O que acontece quando o prazo de 15 dias vence não é um requisito; FR7.1 só alerta e é Should. | Dizer se o vencimento gera evento de auditoria e métrica (NFR8) como parte do Must, ou registrar como premissa que só o alerta existe. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR1.4 | "Revisão jurídica registrada" não tem forma definida (campo, arquivo, quem registra), então o critério "teste de configuração" não é verificável. | Declarar o mecanismo do registro (por exemplo, uma variável de configuração com data e responsável) e o resultado esperado quando faltar. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR4.1 e FR4.2 | Não há tratamento do caso de a versão vigente mudar entre a leitura e o aceite (versão aceita diferente da vigente) nem do aceite repetido de versão já superada. | Definir a resposta da API quando a versão enviada no aceite não é a vigente, com critério de teste. | Resolved (reviewer) |
| R-07 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/inception/requirements-analysis/requirements.md > FR5.2c | O pedido recebido por e-mail é registrado pelo administrador, mas não diz como se confirma que quem escreveu é o titular da conta antes de liberar o resumo de Acesso ou anonimizar. | Acrescentar uma regra simples de verificação de identidade para pedidos fora do app, ou registrar a decisão como premissa com o motivo. | New |

> R-01 Reviewer note: FR3.3 e a premissa de "nenhum convidado real" fecham a lacuna: eventos novos só guardam endereço mascarado e HMAC, os dados de teste são descartados antes da abertura, e o critério de FR6.2 inclui a trilha na busca.

> R-02 Reviewer note: FR2.4 define o comportamento com e sem login e o caminho por e-mail do encarregado, com FR5.2c cobrindo o registro pelo administrador; há critério testável.

> R-03 Reviewer note: FR5.2 define a entrega da resposta e do motivo na Conta; FR5.2a e FR5.2b definem o conteúdo de Acesso e Correção, com critérios.

> R-04 Reviewer note: FR5.7 trata o vencimento do prazo como requisito com evento único, métrica e saúde `DEGRADED`, com critério com relógio injetável.

> R-05 Reviewer note: FR1.4 define o registro da revisão no arquivo da versão (data e revisor) e o critério com três versões.

> R-06 Reviewer note: FR4.2 trata versão superada (`409`, `TERMS_VERSION_OUTDATED`) e aceite repetido, com critério.

### Summary

Os seis achados anteriores foram corrigidos, sem nova contradição, sem seção de revisão no artefato, e os requisitos seguem testáveis e rastreáveis. Resta uma observação menor sobre a verificação de identidade em pedidos recebidos por e-mail, que não impede iniciar o trabalho.
