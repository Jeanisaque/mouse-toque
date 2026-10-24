# Touch Cell

**Criado por Isaque Andrade e Joel Andrade**

Transforma o celular em mouse e teclado do PC, pelo Wi-Fi. Funciona com Android e iPhone, sem instalar nada no celular.

## Como usar

1. **[Baixe o Touch Cell Portátil](https://github.com/Jeanisaque/mouse-toque/releases/latest/download/TouchCell-Portatil.zip)**: já vem com o Python, não precisa instalar nada.
2. Extraia o zip e dê dois cliques em **Iniciar Touch Cell.bat**.
3. Se o Windows perguntar sobre o Firewall, clique em **Permitir**.
4. Leia o QR code com o celular, no mesmo Wi-Fi do PC.

O programa se atualiza sozinho toda vez que abre.

## Gestos

| Gesto | Ação |
|---|---|
| Deslizar 1 dedo | Move o mouse |
| Tocar 1 dedo | Clique esquerdo |
| Tocar 2 dedos | Clique direito |
| Deslizar 2 dedos | Rolar |
| Tocar 2x e segurar | Arrastar |

## Publicar uma atualização

1. Altere `mouse_toque.py` e aumente `VERSAO` (ex.: `1.2` → `1.3`).
2. Coloque o mesmo número em `versao.json`, com as novidades.
3. Envie os dois arquivos para a branch `main`.
