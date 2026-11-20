# AgroSaffer
Proyecto de la escuela

Sistema de monitoreo agrícola con sensores, ESP32, LoRa y energía solar.

## Contenido

| Carpeta | Qué hay |
|---|---|
| `AgroSaffer-App/` | App Android (Kotlin) para ver los datos de los nodos: lecturas, gráficos, historial y alertas. |
| `esp32-code/` | Código Arduino del nodo de campo (ESP32): lee los sensores y envía los datos por LoRa (433 MHz). |
| `3D model/` | Modelo 3D (`agrosaffer.dwg` y `Agrosaffer.zip`). |

## Firebase

El archivo `google-services.json` no está en el repositorio porque tiene la clave del proyecto de Firebase.
Para compilar la app hay que descargarlo desde la consola de Firebase y copiarlo en `AgroSaffer-App/app/`.
