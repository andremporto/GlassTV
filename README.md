# 📺 Porto TvRack

> Um inicializador (launcher) ultra leve, rápido e limpo para Smart TVs Android / Google TV (como as televisões TCL), totalmente livre de anúncios e configurado para o Brasil.

<p align="center">
  <img src="tv_preview.png" alt="Prateleira de Aplicativos com YouCine" width="850">
  <br><br>
  <img src="print_tv_baixo.png" alt="Meteorologia e Painel Financeiro" width="850">
</p>

---

## ✨ O que este projeto tem de especial?

* **Prateleira com 7 Apps:** Acesso direto a YouTube, YouCine, Crunchyroll, Apple TV, Netflix, Prime Video e Navegador Web.
* **Ícone do YouCine Exclusivo:** Desenvolvido sob medida com fundo transparente em alta definição.
* **Clima em Tempo Real:** Pré-configurado para **Aracaju - SE** (latitude: -10.9472, longitude: -37.0731).
* **Painel Financeiro:** Cotações ao vivo de **IBOV**, **S&P 500**, **Bitcoin (BTC)** e **Dólar (USD)**.
* **Zero Lerdeza:** Abre no mesmo segundo em que a TV liga, sem travar a interface.

---

## 🧭 Guia Passo a Passo de Instalação

Siga as etapas na ordem abaixo. Não precisa ter conhecimento técnico: basta ler e copiar os comandos.

---

### Etapa 1: Preparar a sua Televisão 📺

O seu computador precisa de autorização para conversar com a televisão pela rede Wi-Fi.

1. Ligue a TV e pegue o controle remoto.
2. Abra as **Configurações** (ícone de engrenagem) > **Sistema** > **Sobre**.
3. Desça até a opção **Número da Versão** (ou **Build**) e aperte o botão central do controle **7 vezes seguidas**.
   * *Aparecerá um aviso na tela dizendo: "Você agora é um desenvolvedor!".*
4. Volte uma tela, entre em **Opções do desenvolvedor** e ative a opção **Depuração USB**.
5. Descubra o número IP da televisão:
   * Vá em **Configurações** > **Rede e Internet** > clique na sua rede Wi-Fi conectada.
   * Anote os números que aparecem em **Endereço IP** (exemplo: `192.168.3.10`).

---

### Etapa 2: Baixar o Aplicativo 📥

1. No seu computador, vá na seção **[Releases](../../releases)** deste repositório.
2. Na versão **v1.0**, clique no arquivo **`FreeMyTV.apk`** para fazer o download.
3. Deixe o arquivo guardado na sua pasta tradicional de **Downloads**.

---

### Etapa 3: Instalar o ADB no seu Computador 💻

O **ADB** é uma ferramenta do Google que funciona como um "cabo invisível" pela rede Wi-Fi. Escolha abaixo a seção do sistema que você usa:

#### 🪟 Se você usa Windows
1. Baixe o pacote oficial portátil do Google clicando aqui: **[Platform-Tools para Windows](https://dl.google.com/android/repository/platform-tools-latest-windows.zip)**.
2. Abra a sua pasta de Downloads, clique com o botão direito no arquivo baixado e escolha **Extrair Tudo**.
3. Mova o arquivo **`FreeMyTV.apk`** que você baixou na Etapa 2 para **DENTRO** da pasta extraída chamada `platform-tools`.
4. Dentro dessa pasta `platform-tools`, clique na barra de endereço (onde fica o caminho da pasta no topo da janela), digite `cmd` e aperte **Enter**.
   * *Uma janela preta do Prompt de Comando se abrirá já pronta para os comandos.*

---

#### 🐧 Se você usa Linux (Zorin OS, Ubuntu, Debian, Mint)
1. Abra o seu **Terminal** (atalho: `Ctrl + Alt + T`).
2. Instale o ADB rodando o comando:
   ```bash
   sudo apt update && sudo apt install -y adb
   ```
3. Pelo terminal, entre na sua pasta de Downloads onde o arquivo está guardado:
   ```bash
   cd ~/Downloads
   ```

---

#### 🍏 Se você usa Mac (macOS)
1. Abra o **Terminal** (pressione `Command + Barra de Espaço`, digite `Terminal` e aperte **Enter**).
2. Se você usa o gerenciador Homebrew, instale rodando:
   ```bash
   brew install android-platform-tools
   ```
   *(Caso não use o Homebrew, baixe o **[Platform-Tools para Mac](https://dl.google.com/android/repository/platform-tools-latest-darwin.zip)**, descompacte a pasta, mova o arquivo `FreeMyTV.apk` para dentro dela e abra o terminal dentro dessa pasta).*
3. Entre na pasta onde está o arquivo baixado:
   ```bash
   cd ~/Downloads
   ```

---

### Etapa 4: Conectar e Instalar na TV 🚀

Com a janela de terminal/prompt aberta e no mesmo lugar onde está o arquivo `FreeMyTV.apk`, execute os comandos abaixo um por um:

> **Atenção:** Nos comandos abaixo, troque `192.168.3.10` pelo número de IP real da sua TV que você anotou na Etapa 1.

#### 1. Conectar o computador à TV
* **No Linux ou Mac:**
  ```bash
  adb connect 192.168.3.10:5555
  ```
* **No Windows (CMD):**
  ```cmd
  adb connect 192.168.3.10:5555
  ```
* **Olhe para a tela da TV agora:** vai aparecer uma janela perguntando *"Permitir depuração USB?"*. Marque a caixinha *"Sempre permitir deste computador"* e clique em **OK** com o controle remoto.

#### 2. Instalar o launcher
* **No Linux ou Mac:**
  ```bash
  adb install -g FreeMyTV.apk
  ```
* **No Windows (CMD):**
  ```cmd
  adb install -g FreeMyTV.apk
  ```
*(Aguarde alguns segundos até aparecer a palavra **Success**).*

#### 3. Dar permissão de inicialização (essencial para TVs TCL)
* **No Linux ou Mac:**
  ```bash
  adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
  ```
* **No Windows (CMD):**
  ```cmd
  adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
  ```

#### 4. Abrir a aplicação na televisão
* **No Linux, Mac ou Windows:**
  ```bash
  adb shell am start -n io.freemytv/.HomeActivity
  ```

Pronto! A nova interface já estará em execução na Smart TV.

---

## 🛠️ Para Desenvolvedores: Compilação Manual do Código

Caso prefira inspecionar ou alterar o código-fonte antes de gerar o APK no Linux:

```bash
# 1. Instalar pacotes de compilação
sudo apt-get update
sudo apt-get install -y git openjdk-17-jdk adb aapt zipalign python3-pil

# 2. Clonar este repositório
git clone [https://github.com/andremporto/Porto-TV-Rack.git](https://github.com/andremporto/Porto-TV-Rack.git)
cd Porto-TV-Rack

# 3. Compilar o instalador
chmod +x build.sh
./build.sh

# 4. Enviar diretamente para a Smart TV
adb connect IP_DA_SUA_TV:5555
adb install -r -g FreeMyTV.apk
adb shell am start -n io.freemytv/.HomeActivity
```

---

## 📌 Créditos
Baseado no projeto open-source original **Free My TV**, com refinamento visual na prateleira de aplicativos, inclusão de ícone transparente de alta resolução para o YouCine e localização de clima e economia para o Brasil.
