# 📺 GlassTV — Contexto e Memória do Projeto

## 🎯 Visão Geral
O **GlassTV** (`io.freemytv`) é um launcher ultra leve, rápido e moderno para Smart TVs com **Android TV** e **Google TV** (TCL, Sony Bravia, Philips, Philco, Chromecast com Google TV, TV Boxes e Mi Box/Stick), desenvolvido com design **Glassmorphism**, foco em TVs de entrada com pouca memória RAM e adaptado para o público brasileiro.

O projeto foi evoluído a partir da base open-source *Free My TV*, adicionando personalizações profundas e layout refinado de acordo com as diretrizes do **Apple tvOS**:
- **Design Apple tvOS em 3 Blocos Simultâneos**:
  - **Header Superior**: Identificador proeminente e nítido "Glass TV" em Inter Bold preto (`48dp`, tracking `-0.015f`) à esquerda e os 4 botões de vidro circulares nativos à direita (TV Aberta, Entradas, Papel de Parede, Ajustes), com o último alinhado perfeitamente à margem direita (`1840dp`).
  - **Bloco 1 (Informações)**: Cards completos de Meteorologia e Cotações Financeiras lado a lado com a mesma altura (`325dp`) em vidro escuro translúcido com blur (`#161618`) sem bordas brancas e squircles contínuos. O card de cotações acomoda **6 ativos** (IBOV, S&P 500, Bitcoin, Ethereum, Solana e Dólar USDT) e o clima apresenta dados proporcionais e arejados.
  - **Bloco 2 (Destaque Central)**: Prateleira de 7 Apps Essenciais em proporção widescreen 16:9 (`196 x 110.25dp`) sobre bandeja cápsula de vidro escuro com blur (`#161618`), espaçamento elegante de ~51dp entre cards e sombra ambiente suave (`4dp` de drop).
  - **Bloco 3 (Entradas & Atalhos)**: Entradas (TV ao Vivo, HDMI 1, HDMI 2, USB Dinâmico) alinhadas rigorosamente à margem esquerda (`80dp`) e Atalhos de Sistema (Todos os Apps, Ajustes do Início, Ajustes da TV) ancorados encostando na margem direita (`1840dp`), com títulos de seção adaptativos ao fundo.
- **Canvas Padrão**: Fundo em degradê radial amarelo radiante e âmbar dourado (`#FACD46` -> `#C85505`), limpo e acolhedor.
- **Tipografia Universal**: Fonte **Inter** (`assets/fonts/InterVariable.ttf`) em todos os títulos, textos e nomes de ícones.
- **Detecção Dinâmica de Pen Drive / USB**: Ícone inteligente no bloco de Entradas que surge apenas quando armazenamento USB externo está montado, abrindo o gerenciador de arquivos da TV ao ser selecionado.
- **53 Papéis de Parede Nativos em HD**: Galeria integrada em 1080p e miniaturas dedicadas de 320x180.
- **Consumo de Memória Extremo (< 6 MB Java Heap)**: Bitmaps em `RGB_565` e reciclagem agressiva de views e memória.

---

## 🏗️ Arquitetura e Estrutura de Pastas

```text
GlassTV/
├── app/
│   ├── AndroidManifest.xml   # Configurações do app, HomeActivity, permissões e intents de launcher
│   ├── assets/
│   │   ├── fonts/            # Fontes tipográficas locais
│   │   ├── i18n/             # Traduções e internacionalização
│   │   ├── logos/            # Logos em alta resolução dos apps de streaming (ex: youcine.png)
│   │   ├── symbols/          # Ícones e símbolos do sistema (incluindo usb.png)
│   │   ├── wallpapers/       # 53 papéis de parede nativos em 1080p e miniaturas
│   │   └── zones.json        # Fusos horários e mapeamento geográfico
│   ├── res/                  # Recursos Android (layouts, drawables, values)
│   └── src/io/freemytv/      # Código-fonte Java nativo
│       ├── HomeActivity.java     # Activity principal do Launcher
│       ├── HomeApps.java         # Gerenciamento de apps, atalhos, logos, detecção USB e prateleira
│       ├── HomeView.java         # Renderização gráfica da tela inicial (3 blocos tvOS)
│       ├── Wallpapers.java       # Seletor e renderização dos 53 papéis de parede
│       ├── Place.java            # Localização geográfica, fuso e clima
│       ├── Recs.java             # Painel de cotações financeiras e meteorologia
│       └── ...                   # Outras classes de suporte e renderização
├── tools/                    # Scripts Python de automação (wallpapers, logos, tradução)
├── build.sh                  # Script de compilação autônomo (SEM Gradle) -> gera GlassTV-Light.apk
├── deploy.sh                 # Script de compilação e deploy direto via ADB na TV (GlassTV-Light.apk)
├── config.properties         # Configurações personalizadas (cidade, coordenadas, moeda, etc.)
├── GlassTV.apk               # Binário da versão completa (com YouTube feed, branch 'full')
└── GlassTV-Light.apk         # Binário da versão light (tvOS 3 blocos, ultra rápida, branch 'main')
```

---

## ⚙️ Compilação e Deploy (Sem Gradle)

O projeto dispensa o Gradle e usa diretamente a pipeline de ferramentas do Android SDK:
1. **`build.sh`**:
   - Lê `config.properties` e gera `Config.java`
   - Compila recursos com `aapt2`
   - Compila classes Java com `javac` (JDK 17+)
   - Gera bytecode DEX com `d8`
   - Empacota e assina com `zipalign` e `apksigner` usando `freemytv.keystore`
   - Saída: `GlassTV-Light.apk`
2. **`deploy.sh`**:
   - Executa `./build.sh`
   - Conecta via ADB na TV (`adb connect IP:5555`)
   - Instala o APK (`adb install -r --no-incremental GlassTV-Light.apk`)
   - Abre a `HomeActivity` (`adb shell am start -n io.freemytv/.HomeActivity`)

---

## 💡 Diretrizes para Edições e Novas Funcionalidades
- **Preservação de Memória**: Sempre manter decodificação de imagens em `Bitmap.Config.RGB_565` para não estourar a memória em TVs com 1 GB / 1.5 GB de RAM.
- **Compatibilidade D-Pad / Controle Remoto**: Toda navegação precisa ser 100% navegável pelas setas direcionais (D-Pad), botões Back e Select/Enter.
- **Configuração Local**: Manter localização e formatação em Português do Brasil (`pt-BR`, moeda BRL, temperatura em °C).

## Diretrizes de Interface e Design
- Sempre utilize a skill global `apple-design` (com foco nas diretrizes de tvOS/macOS) para decisões de UI, tokens de cor, tipografia e espaçamento.
- Siga os princípios de design da Apple: cantos contínuos (squircles), tipografia escalável, contraste nítido e estados de foco evidentes (ampliação suave/brilho no D-Pad) respeitando o limite de memória dos dispositivos.

