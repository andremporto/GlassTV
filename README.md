# Porto-TV-Rack

Launcher minimalista e veloz para Smart TVs Android / Google TV (TCL e compatíveis), sem anúncios, com arranque instantâneo e totalmente localizado para o Brasil.

---

## ✨ Recursos

* **Prateleira com 7 Aplicações:** YouTube, YouCine, Crunchyroll, Apple TV, Netflix, Prime Video e Navegador Web.
* **Logótipo YouCine Nativo:** Ícone integrado com canal alfa (fundo transparente) de alta definição.
* **Meteorologia e Previsão Local:** Pré-configurado para **Aracaju - SE** (latitude: -10.9472, longitude: -37.0731).
* **Painel Financeiro em Tempo Real:** Acompanhamento automático de **IBOV**, **S&P 500**, **Bitcoin (BTC)** e **Dólar (USD)**.
* **Leve e Ágil:** Desenvolvido sem serviços pesados em segundo plano.

---

## 🚀 Instalação Rápida (via ADB)

1. Ative a **Depuração USB** nas opções de desenvolvedor da sua televisão.
2. Com o computador conectado à mesma rede Wi-Fi da televisão, execute no terminal:

```bash
# Conectar à televisão (confirme a autorização no ecrã com o controle remoto)
adb connect IP_DA_SUA_TV:5555

# Instalar a aplicação concedendo todas as permissões
adb install -g FreeMyTV.apk

# Permitir arranque automático (essencial em televisores TCL)
adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"

# Abrir o launcher
adb shell am start -n io.freemytv/.HomeActivity
```

---

## 🛠️ Compilação Manual

Caso queira clonar e compilar localmente:

```bash
# Instalar dependências (Ubuntu / Debian / Zorin OS)
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
Baseado no projeto de código aberto **Free My TV**, com ajustes estéticos na prateleira, assets em alta resolução e localização de dados para o Brasil.
