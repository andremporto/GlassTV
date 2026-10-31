#!/usr/bin/env bash
set -euo pipefail
TV_IP="${1:-192.168.3.10:5555}"

cd "$(dirname "$0")"

echo "==> 🔨 Compilando APK..."
./build.sh

echo "==> 📺 Garantindo conexão ADB ($TV_IP)..."
adb connect "$TV_IP"

echo "==> 🚀 Instalando APK na Smart TV..."
adb -s "$TV_IP" install -r GlassTV.apk

echo "==> 🎬 Abrindo HomeActivity na TV..."
adb -s "$TV_IP" shell am start -n io.freemytv/.HomeActivity

echo "==> ✅ Concluído com sucesso!"
