# Personas — Trading REAL

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md` e `user-stories-questions.md` (Q1).

## P1 — Fundador operador

- **Papel:** dono do produto; o primeiro a operar REAL, com a própria carteira, na mainnet.
- **Objetivos:** validar o REAL com dinheiro próprio antes de abrir; ver cada ordem, taxa e resultado conciliados com a rede.
- **Dores:** não tem segundo revisor; precisa de limites e de um kill switch em que possa confiar.
- **Familiaridade técnica:** alta.
- **Frequência:** diária durante o piloto.

## P2 — Convidado do beta

- **Papel:** um dos até 20 convidados liberados depois do fundador.
- **Objetivos:** deixar o bot operar sozinho dentro de um teto que ele mesmo escolhe; sacar quando quiser.
- **Dores:** medo de perder mais do que aceitou; desconfiança de entregar fundos a um sistema.
- **Familiaridade técnica:** média (usa Phantom ou Solflare).
- **Frequência:** algumas vezes por semana.

## P3 — Administrador

- **Papel:** o próprio fundador, na entrada administrativa.
- **Objetivos:** liberar o REAL por usuário com quarentena, baixar tetos, bloquear tokens, acionar o kill switch, ver divergências.
- **Dores:** precisa agir rápido num incidente, sozinho.
- **Familiaridade técnica:** alta.
- **Frequência:** diária no piloto; imediata em incidente.

## Relação e prioridade

P1 vem primeiro (liberação na carteira do fundador); P2 depois da quarentena; P3 sustenta as duas. Prioridade: P1, P3, P2.
