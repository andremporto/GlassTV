# Porto TvRack

Launcher minimalista e veloz para Smart TVs Android / Google TV (TCL e compatíveis), sem anúncios, com arranque instantâneo e totalmente localizado para o Brasil.

<p align="center">
  <img src="tv_preview.png" alt="Prateleira de Aplicativos com YouCine" width="850">
  <br><br>
  <img src="print_tv_baixo.png" alt="Meteorologia e Painel Financeiro" width="850">
</p>

---

## ✨ Recursos

* **Prateleira com 7 Aplicações:** YouTube, YouCine, Crunchyroll, Apple TV, Netflix, Prime Video e Navegador Web.
* **Logótipo YouCine Nativo:** Ícone integrado com canal alfa (fundo transparente) de alta definição.
* **Meteorologia e Previsão Local:** Pré-configurado para **Aracaju - SE** (latitude: -10.9472, longitude: -37.0731).
* **Painel Financeiro em Tempo Real:** Acompanhamento automático de **IBOV**, **S&P 500**, **Bitcoin (BTC)** e **Dólar (USD)**.
* **Leve e Ágil:** Desenvolvido sem serviços pesados em segundo plano.

---

## 🚀 Instalação Rápida (via APK)

Para quem quer apenas instalar e usar na TV sem precisar compilar:

### 1. Baixar o APK
Acesse a aba **[Releases](../../releases)** na coluna à direita deste repositório e baixe o arquivo `FreeMyTV.apk`.

### 2. Ativar a Depuração USB na TV
1. Acesse as **Configurações** da TV > **Sistema** > **Sobre**.
2. Clique 7 vezes seguidas sobre **Número da Versão (Build)** até surgir a notificação *"Você agora é um desenvolvedor"*.
3. Volte em **Sistema** > **Opções do desenvolvedor** e ative a **Depuração USB**.
4. Verifique o endereço IP da televisão nas opções de rede (ex.: `192.168.3.10`).

### 3. Instalar via ADB pelo Computador
Com o pacote `adb` instalado no terminal, execute:

```bash
# 1. Conectar à televisão (confirme o diálogo na tela com o controle remoto)
adb connect IP_DA_SUA_TV:5555

# 2. Instalar a aplicação concedendo as permissões
adb install -g FreeMyTV.apk

# 3. Permitir inicialização automática (essencial para televisores TCL)
adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"

# 4. Iniciar o launcher
adb shell am start -n io.freemytv/.HomeActivity
```

---

## 🛠️ Compilação Manual

Caso prefira inspecionar o código e compilar localmente:

```bash
# Instalar dependências básicas (Ubuntu / Zorin OS / Debian)
sudo apt-get update
sudo apt-get install -y git openjdk-17-jdk adb aapt zipalign

# Clonar o repositório
git clone [https://github.com/andremporto/Porto-TV-Rack.git](https://github.com/andremporto/Porto-TV-Rack.git)
cd Porto-TV-Rack

# Compilar
./build.sh
```

---

## 📌 Créditos
Baseado no projeto open-source **Free My TV**, com refinamento visual da prateleira, asset transparente de alta definição para o YouCine e localização completa para o Brasil.
