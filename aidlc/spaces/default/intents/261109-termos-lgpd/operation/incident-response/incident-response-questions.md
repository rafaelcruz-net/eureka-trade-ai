# Perguntas do Incident Response

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10). Base: o Incident Response do intent `261026-memetrade-saas-mvp` (RB-01 a RB-12, plano, matriz de escalonamento, RTO e RPO), que continua valendo.

## Pergunta 1
Quais modos de falha novos este intent traz?

A) Os dos alarmes Altos novos de `operation/observability-setup/alarms.md`: pedido do titular vencido, anonimização falhando repetidas vezes e tentativa de gravar carteira completa na trilha; mais o vazamento da chave HMAC da trilha (no RB-09) e a trava de novo aceite barrando quem já aceitou (recomendado)
B) Só os alarmes Altos
X) Other (please specify)

[Answer]: A

## Pergunta 2
Escalonamento e plantão?

A) Sem mudança: o responsável do projeto é o único da escala; o revisor independente (C-14) entra em qualquer incidente que envolva funções REAL, que ainda não existem (recomendado)
B) Outra escala
X) Other (please specify)

[Answer]: A

## Pergunta 3
Remediação automática?

A) Nenhuma automática para dados pessoais: anonimização, pedidos do titular e trilha só mudam por ação do administrador ou por correção de código revisada; os expurgos se recuperam sozinhos na execução seguinte (recomendado: ações sobre dados pessoais são irreversíveis)
B) Reexecutar a anonimização automaticamente
X) Other (please specify)

[Answer]: A

## Pergunta 4
Comunicação durante incidentes com dados pessoais?

A) Seguir o plano do intent anterior e, quando o incidente puder causar risco ou dano relevante aos titulares, preparar a comunicação à ANPD e aos titulares afetados, com o encarregado e conforme o parecer jurídico (C-09) (recomendado: obrigação da LGPD)
B) Só o registro interno
X) Other (please specify)

[Answer]: A

## Pergunta 5
RTO e RPO?

A) Os do intent anterior; a anonimização não tem RPO próprio porque é irreversível por desenho (backup restaurado não pode "desanonimizar" ninguém sem novo pedido) (recomendado)
B) Outros valores
X) Other (please specify)

[Answer]: A
