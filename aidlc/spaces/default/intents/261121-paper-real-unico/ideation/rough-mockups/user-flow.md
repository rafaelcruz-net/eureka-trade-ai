# Fluxo do usuário — paper-real-unico

Fonte: `rough-mockups-questions.md` (Q2 e Q3).

## Caminho feliz [Q2]

```
[Cria bot SIMULADO] --> [Acompanha o P&L do bot em US$]
         |
         v
[Decide testar com dinheiro real]
         |
         v
[Cria bot REAL com a mesma estratégia] --(precisa da carteira do bot ativa, das regras do REAL)
         |
         v
[Lista de bots: os dois lado a lado, com o modo em texto]
         |
         v
[Tela P&L: bloco Simulado (US$) | bloco Real (SOL), totais separados]
         |
         v
[Detalhe do bot REAL: linhas "fechado por liquidação" ou "fechado por condicional"]
```

Fallback em texto, para quem não consegue ver o diagrama:
1. O usuário cria um bot simulado e acompanha o P&L dele em dólar.
2. Ele decide testar com dinheiro real.
3. Ele cria um bot real com a mesma estratégia. Para isso, a carteira do bot precisa estar ativa.
4. Na lista, ele vê os dois bots, com o modo escrito.
5. Na tela de P&L, ele compara cada bot dentro do próprio modo e vê os totais separados.
6. No detalhe do bot real, ele vê as linhas de liquidação e de condicional.

## Desvios [Q2]

- **Carteira do bot inativa, ou REAL desligado ou não liberado.** Na criação do bot, a opção REAL aparece indisponível, com o motivo em texto e o link "Ativar a carteira do bot". O bot simulado continua disponível.
- **Painel.** Mostra os dois totais e leva à tela de P&L.

## Arquitetura da informação [Q3]

```
Resultado
├── Simulado (US$)
│   ├── Total (realizado, não realizado, taxas)
│   ├── Bots simulados (P&L de cada um)
│   └── Aviso: o simulado não garante o real
└── Real (SOL)
    ├── Total (realizado, não realizado, taxas)
    └── Bots reais (P&L de cada um, com as linhas rateadas marcadas)
```

Nunca existe um nó "Total geral" que junte os dois modos.

## Assumptions & Open Questions

None.
