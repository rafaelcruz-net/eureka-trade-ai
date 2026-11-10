# Requisitos de segurança — U2 `u2-retention`

Conversa: português (Brasil). Fontes:
- `construction/u2-retention/functional-design/` (BR2.1 a BR2.4, BR2.15, BR2.19);
- `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR6);
- `inception/contract-design/contract-summary.md` (J6);
- `nfr-requirements-questions.md` (Q2);
- regras do projeto: nada de dado pessoal em log e segredo só da configuração.

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | Nenhum evento novo da trilha nem da saída da trilha contém endereço completo de carteira. A carteira vai só como máscara (`actor`) e HMAC (`walletHmac`) | teste de integração que cria conta, faz login (aceito e recusado, convidado e administrador) e procura o endereço completo em `audit_event` e `audit_outbox`: não encontra |
| NFR1.2 | O validador da auditoria recusa todo valor de `details` com a forma de um endereço Base58 completo (32 a 44 caracteres). O log da recusa traz o campo e o motivo, nunca o valor | teste de unidade do validador |
| NFR1.3 | Um login recusado com texto que não é carteira grava `actor=anonymous`, sem `walletHmac`, e `details` só com `addressFormat=INVALID`. O HMAC nunca é calculado sobre esse texto (BR2.15) | teste de unidade dos dois serviços de login |
| NFR1.4 | Os expurgos e os indicadores de saúde registram só totais, duração e classe do erro, nunca carteira, nonce, digest ou identificador de pessoa | teste de log (captura) |
| NFR2.1 | Os expurgos são tarefas de sistema e apagam linhas de todos os tenants sem expor nenhuma. O evento de contagem é publicado pelo `system`, sem tenant, pessoa ou lista de IDs | teste de integração com dois tenants |
| NFR6.1 | A chave do HMAC vem só da configuração (`MEMETRADE_AUDIT_WALLET_HMAC_KEY`: `.env` local, gerenciador de segredos na nuvem), tem pelo menos 32 bytes e nunca aparece em log, resposta, `actuator/env` nem em `NEXT_PUBLIC_*`. Sem ela, a API e o worker não sobem (BR2.4) | teste de configuração; varredura de segredos da CI |
| NFR6.2 | O `.env.example` traz só o nome da variável. O `verify.sh` gera uma chave efêmera por execução, e o passo `Compose config` da CI usa um valor de exemplo; as duas mudanças ficam com o responsável do projeto | o `ComposeFilesTest` confere que o `.env.example` tem o nome sem valor; o `verify.sh` passa no ponto de controle da U2 (a API não sobe sem a chave, NFR7.5) |
| NFR6.3 | A chave não tem rotação no beta. A troca só acontece por incidente (vazamento), registrada no runbook, e quebra a correlação entre eventos anteriores e posteriores (BR2.19) | passa quando o runbook de incidentes tem o passo "troca da chave HMAC da auditoria", com o efeito na correlação (verificação manual do responsável no Build and Test) |
| NFR6.4 | Os expurgos usam a conexão principal do Identity, dono das três tabelas. Nenhum outro módulo lê ou apaga essas tabelas (Q2) | teste de arquitetura existente (ArchUnit) |

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Endereço completo vazando pela trilha ou pela saída da trilha | NFR1.1, NFR1.2 |
| Texto do atacante usado para gerar HMAC ou gravado na trilha | NFR1.3 |
| Correlação da pessoa sem a chave | o HMAC sem a chave não identifica (NFR6.1) |
| Chave exposta na configuração ou no cliente | NFR6.1, NFR6.2 |
| Dado vencido guardado além do prazo | expurgos (reliability-requirements.md) |
