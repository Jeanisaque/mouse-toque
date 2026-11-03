# Criador de Sites (APK)

App Android para criar sites direto no celular. Escolha um modelo (negócio local, restaurante, loja,
portfólio, página de venda, evento), preencha o conteúdo, ajuste cores e fonte, veja a prévia e
exporte um site completo em **um único arquivo .html** (salvar em Downloads, compartilhar ou abrir).

- Funciona offline; os sites ficam salvos no aparelho ("Meus sites").
- O arquivo gerado pode ser publicado em qualquer hospedagem (GitHub Pages, Netlify, etc.).
- O editor fica em `app/src/main/assets/index.html`; o Kotlin só abre a tela e salva/compartilha arquivos.

## Gerar o APK

Push na `main` com mudanças em `criador-sites/` roda o workflow **Criador de Sites (Android)**
e publica `CriadorDeSites.apk` na release `criador-sites`. Também dá para rodar manualmente (workflow_dispatch).
Localmente: `cd criador-sites && gradle assembleDebug`.
