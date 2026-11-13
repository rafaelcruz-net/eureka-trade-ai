# Plano de resposta a incidentes

Conversa: português (Brasil). O plano do intent anterior continua valendo (`261026-memetrade-saas-mvp/operation/incident-response/incident-plan.md`): severidades P1 a P4, registro em `docs/incidents/`, contenção pelo kill switch da U8, revisão pós-incidente obrigatória para P1 e P2 com linha do tempo, causa raiz e prevenção (`phases/operation.md`). Este intent acrescenta os incidentes com dados pessoais, que a LGPD trata à parte (resposta 4A).

## Incidente com dados pessoais

É todo incidente em que um dado pessoal pode ter sido acessado, vazado, alterado ou perdido sem autorização: por exemplo, carteira completa num log ou numa tabela fora da trilha (RB-15), acesso entre tenants (RB-10), vazamento da chave HMAC (RB-09) ou anonimização que não removeu tudo (`AnonymizationNoWalletLeftIT` existe para impedir isso).

| Passo | Quando | O quê |
|---|---|---|
| 1. Conter | imediato | parar a fonte (rollback, kill switch, troca de segredo) sem apagar evidência |
| 2. Registrar | primeiro minuto | abrir `docs/incidents/AAAA-MM-DD-<slug>.md` com o que se sabe, sem copiar o dado pessoal para o registro |
| 3. Avaliar o risco | nas primeiras horas | quais dados, de quantos titulares, por quanto tempo expostos e se o incidente pode causar risco ou dano relevante |
| 4. Comunicar | quando houver risco ou dano relevante | o encarregado prepara a comunicação à ANPD e aos titulares afetados, dentro do prazo da regulação da ANPD e conforme o parecer jurídico (C-09); o texto descreve os dados, os titulares, as medidas tomadas e os riscos |
| 5. Corrigir | depois de conter | correção por código revisado, com teste de regressão |
| 6. Revisão pós-incidente | sempre, para incidente com dados pessoais | linha do tempo, causa raiz, prevenção e cópia do que foi comunicado |

O e-mail e o canal do encarregado são os mesmos publicados na `/privacidade` (campo de implantação, ainda "a definir" no ambiente de teste).

## RTO e RPO

Sem mudança em relação ao intent anterior (resposta 5A). A anonimização não tem RPO próprio: é irreversível por desenho, e restaurar um backup anterior a ela **não** devolve a conta. Depois de qualquer restauração, reaplicar as anonimizações registradas na trilha (`ACCOUNT_ANONYMIZED`) antes de liberar o acesso.

## Exercícios

Antes de abrir o beta multiusuário (G1), um exercício de mesa por ano, cobrindo: pedido de Exclusão vencido (RB-13), anonimização falhando (RB-14) e vazamento da chave HMAC (RB-09).
