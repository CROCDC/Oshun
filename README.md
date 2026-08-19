# Oshun GPS Bridge

App Android que comparte el **GPS del teléfono** con la app **Navionics / Garmin
Boating** de la tablet, por Wi‑Fi, emitiendo sentencias **NMEA 0183**. Navionics
la reconoce como una fuente de posición externa (igual que un gateway NMEA de barco).

> Ver [`PLAN.md`](PLAN.md) para el diseño completo y la justificación técnica.

## Descargar la app

- **Último APK (recomendado):** [Releases → `debug-latest`](https://github.com/CROCDC/Oshun/releases/tag/debug-latest) → descargá `app-debug.apk`. Es público, sin login y sin descomprimir; se actualiza en cada build verde (que pasó unit + instrumentados).
- **Todas las versiones:** [página de Releases](https://github.com/CROCDC/Oshun/releases).

En el teléfono, la primera vez hay que permitir "instalar apps de orígenes desconocidos".

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

## Si Navionics se queda con una posición vieja

Lo más engañoso de este puente es que puede *parecer* que anda mientras la tablet
mira una posición congelada. La app está construida para que eso no pase, y para
que cuando pase se pueda diagnosticar en 10 segundos:

| Qué lo causaba | Qué hace la app ahora |
|---|---|
| Con la pantalla apagada el CPU se suspende y el Wi‑Fi entra en ahorro (un foreground service **no** evita ninguna de las dos) | Toma un `PARTIAL_WAKE_LOCK` y un `WifiLock` mientras transmite |
| El GPS deja de entregar fixes y el stream se queda mudo: Navionics sigue dibujando la última posición | **Heartbeat**: reenvía el último fix en cada intervalo y, si ya está viejo, lo manda marcado como inválido (`RMC` status `V`, `GGA` quality `0`) |
| La tablet se desconecta sin cerrar limpio: los `write` siguen entrando al buffer del kernel y la app informa "1 cliente" durante minutos | Cada cliente TCP tiene un hilo lector que detecta el fin de stream y lo da de baja al instante |
| El sistema reinicia el servicio y, sin extras en el intent, arrancaba con los valores por defecto del código | La configuración se persiste y se relee en el reinicio; iniciar con otra config ahora **reinicia** los transportes en vez de ignorarla |
| El apagado automático a los 15 min sin clientes se ejecutaba en silencio | Es un switch (se puede desactivar), avisa por notificación y la app explica al abrirse por qué se apagó |

**Cómo diagnosticar en la cancha**, mirando la tarjeta de estado:

1. **Último fix del GPS** crece sin parar → el GPS dejó de entregar (bajo techo, Doze,
   optimización de batería). El banner de optimización de batería lleva a excluir la app.
2. **Último envío a un cliente** crece sin parar → nadie está conectado: revisá la IP,
   el puerto y el emparejamiento en Navionics.
3. **Clientes TCP = 1** y los dos contadores frescos → el puente está entregando; si la
   carta igual no se mueve, el problema está del lado de la tablet.
4. Prueba definitiva desde otra máquina en la misma red: `nc <ip-del-telefono> 2000`
   tiene que escupir `$GPRMC` continuamente.

## Estructura

```
app/
  src/main/java/com/oshun/gpsbridge/
    model/Fix.kt               modelo de posición, sin dependencias de plataforma
    nmea/NmeaFormatter.kt      genera NMEA 0183 + checksum (puro, testeable)
    net/NmeaTransport.kt       interfaz común de transporte
    net/NmeaTcpServer.kt       servidor TCP
    net/NmeaUdpBroadcaster.kt  broadcast UDP
    net/NetworkUtils.kt        IP local para mostrar en la UI
    core/BridgeState.kt        config + estado observable por la UI
    core/BridgeLogic.kt        lógica pura del service (transportes, heartbeat, staleness, edades)
    core/ConfigCodec.kt        serializa la config para que sobreviva un reinicio del servicio
    core/StopReason.kt         por qué se detuvo el puente (usuario / apagado por inactividad)
    store/ConfigStore.kt       persistencia (SharedPreferences) de config y último motivo de apagado [Android]
    location/LocationSource.kt FusedLocationProvider → Fix (Flow) [Android]
    service/GpsBridgeService.kt foreground service, pantalla apagada [Android]
    MainActivity.kt            UI (Jetpack Compose) [Android]
  src/test/java/com/oshun/gpsbridge/
    model/ nmea/ net/ core/    tests unitarios del core (JUnit)
verify/                        proyecto JVM que corre esos tests + cobertura sin Android SDK
```

Los paquetes `[Android]` son glue de ciclo de vida / UI; toda la lógica de trabajo
vive en `model/`, `nmea/`, `net/` y `core/`, que son Kotlin puro y están cubiertos
por tests.

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

### Tests y cobertura

Tres niveles de test:

1. **Core puro (JVM, sin Android SDK)** — `verify/` compila los mismos archivos de
   `model/`, `nmea/`, `net/` y `core/` y aplica un **gate de cobertura del 90%**:
   ```bash
   cd verify && gradle test jacocoTestCoverageVerification
   ```
   Cobertura del core (instrucciones): **98%** — el gate falla el build si baja del 90%.

2. **Unitarios de la app (Robolectric)** — `GpsBridgeServiceTest` arranca el
   foreground service con un GPS falso y verifica el envío de NMEA por socket real:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

3. **Instrumentados (emulador)** — `MainActivityTest` (Compose) corre en un emulador:
   ```bash
   ./gradlew connectedDebugAndroidTest
   ```

**Cobertura mergeada del módulo app** (unit + instrumentado): ~**90%+ líneas**. El
task `jacocoMergedReport` une las dos ejecuciones. En CI, cada push imprime los
porcentajes en el log/summary y sube los reportes HTML como artifacts
(`coverage-report`, `coverage-report-instrumented`). El único código excluido del
denominador es `LocationSource` (wrapper de GPS real, no testeable sin hardware).

## Permisos

`ACCESS_FINE_LOCATION` (GPS), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`
(seguir transmitiendo con la pantalla apagada), `POST_NOTIFICATIONS` (Android 13+),
`INTERNET` / `ACCESS_WIFI_STATE` (red), `WAKE_LOCK` (seguir transmitiendo con el CPU
suspendido) y `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (ofrecer la exclusión del ahorro
de batería, opcional: solo se usa cuando tocás el botón del banner).

## Estado

- [x] Core NMEA (RMC/GGA + checksum) con tests que pasan
- [x] Transportes TCP y UDP con tests (sockets reales en loopback)
- [x] Cobertura del core 98% con gate del 90% en CI; app mergeada ~93% instrucciones / 97% líneas
- [x] Foreground service + UI
- [x] Intervalo de actualización del GPS configurable (0.5 / 1 / 2 / 5 s)
- [x] Lectura de consumo de batería (nivel %, mA instantáneos, caída %/h)
- [x] Wake lock + Wi-Fi lock, heartbeat de reenvío y detección de cliente TCP muerto
- [x] Config persistida entre reinicios del servicio + apagado automático configurable
- [x] Diagnóstico en pantalla: edad del último fix, del último envío y motivo del último apagado
- [x] Código en inglés; todos los textos de UI en `res/values/strings.xml`
- [x] CI que compila el APK y lo publica como artifact
- [ ] Prueba de campo real contra Navionics (pendiente de hardware)
- [ ] Fuente iOS (fase 2)
