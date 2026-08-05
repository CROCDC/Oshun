# Oshun GPS Bridge

App Android que comparte el **GPS del teléfono** con la app **Navionics / Garmin
Boating** de la tablet, por Wi‑Fi, emitiendo sentencias **NMEA 0183**. Navionics
la reconoce como una fuente de posición externa (igual que un gateway NMEA de barco).

> Ver [`PLAN.md`](PLAN.md) para el diseño completo y la justificación técnica.

## Cómo funciona

```
Teléfono (esta app)                         Tablet (Navionics)
  lee GPS (1 Hz)                              Paired Devices:
  → arma NMEA ($GPRMC/$GPGGA)   ── Wi‑Fi ──▶    IP = <ip del telefono>
  → TCP :2000  y/o  UDP :2000                   Port = 2000
                                                Protocol = TCP o UDP
```

Ambos dispositivos deben estar en la **misma red Wi‑Fi** (o la tablet conectada
al **hotspot** del teléfono).

## Dos transportes (elegís cuál usar)

La app puede emitir por los dos a la vez; después probás cuál te funciona mejor:

| Transporte | Cómo lo usa Navionics | Notas |
|-----------|------------------------|-------|
| **TCP** | Paired Devices → Protocol **TCP**, host = IP del teléfono, port = 2000 | Servidor; 1 cliente a la vez es lo típico. El más fiable. |
| **UDP** | Paired Devices → Protocol **UDP**, port = 2000 | Broadcast a la subred; varios clientes. Puede fallar con *AP isolation*. |

## Emparejar en la tablet

1. Conectá la tablet a la misma Wi‑Fi que el teléfono (o al hotspot del teléfono).
2. Abrí la app en el teléfono, elegí TCP/UDP y puerto, y tocá **Iniciar**. Anotá la **IP** que muestra.
3. En Navionics: **Menú → Paired Devices → Add device manually**.
4. Ingresá **Host = IP del teléfono**, **Port = 2000**, **Protocol = TCP** (o UDP) y guardá.
5. La posición del teléfono aparece y se mueve en la carta.

## Estructura

```
app/
  src/main/java/com/oshun/gpsbridge/
    model/Fix.kt              modelo de posición, sin dependencias de plataforma
    nmea/NmeaFormatter.kt     genera NMEA 0183 + checksum (pura, testeable)
    net/NmeaTransport.kt      interfaz común de transporte
    net/NmeaTcpServer.kt      servidor TCP
    net/NmeaUdpBroadcaster.kt broadcast UDP
    net/NetworkUtils.kt       IP local para mostrar en la UI
    location/LocationSource.kt FusedLocationProvider → Fix (Flow)
    service/GpsBridgeService.kt foreground service (funciona con pantalla apagada)
    BridgeState.kt            config + estado observable por la UI
    MainActivity.kt           UI (Jetpack Compose)
  src/test/java/.../NmeaFormatterTest.kt  tests del core NMEA
verify/                       proyecto JVM para correr los tests NMEA sin Android SDK
```

## Descargar el APK ya compilado

No hace falta compilar nada localmente: cada push corre el workflow
**Build APK** (GitHub Actions) y publica el `app-debug.apk` como *artifact*.
En GitHub → pestaña **Actions** → última corrida del branch → sección
**Artifacts** → `oshun-gps-bridge-debug`. Lo descomprimís y lo instalás en el
teléfono (hay que permitir "instalar apps de orígenes desconocidos").

## Build local

Requiere el Android SDK (compileSdk 35). Con Android Studio: abrir la carpeta y
*Run*. Por línea de comando:

```bash
./gradlew assembleDebug        # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # instala en un dispositivo conectado
./gradlew test                 # tests unitarios del core NMEA
```

### Verificar el core NMEA sin Android SDK

El módulo `verify/` compila los mismos archivos `model/` y `nmea/` en un build JVM
puro, para correr los tests sin el Android SDK:

```bash
cd verify && gradle test
```

## Permisos

`ACCESS_FINE_LOCATION` (GPS), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`
(seguir transmitiendo con la pantalla apagada), `POST_NOTIFICATIONS` (Android 13+),
`INTERNET` / `ACCESS_WIFI_STATE` (red).

## Estado

- [x] Core NMEA (RMC/GGA + checksum) con tests que pasan
- [x] Transportes TCP y UDP
- [x] Foreground service + UI
- [ ] Prueba de campo real contra Navionics (pendiente de hardware)
- [ ] Fuente iOS (fase 2)
