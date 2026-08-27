# Oshun GPS Bridge

App Android que comparte el **GPS del teléfono** con la app **Navionics / Garmin
Boating** de la tablet, por Wi‑Fi, emitiendo sentencias **NMEA 0183**. Navionics
la reconoce como una fuente de posición externa (igual que un gateway NMEA de barco).

> Ver [`PLAN.md`](PLAN.md) para el diseño completo y la justificación técnica.

## Descargar la app

- **Último APK (recomendado):** [Releases → `debug-latest`](https://github.com/CROCDC/Oshun/releases/tag/debug-latest) → descargá `app-debug.apk`. Es público, sin login y sin descomprimir; se actualiza en cada build verde (que pasó unit + instrumentados).
- **Todas las versiones:** [página de Releases](https://github.com/CROCDC/Oshun/releases).

En el teléfono, la primera vez hay que permitir "instalar apps de orígenes desconocidos".

La app misma muestra abajo de todo qué build tenés instalado (versión + número de build +
commit) y un botón **Descargar la última versión** que abre esa página de Releases, así
podés comparar sin salir a buscar. El número de build sube en cada APK publicado: si el de
Releases es mayor que el tuyo, estás atrasado.

### Actualizar encima de la versión instalada

Se instala encima, sin desinstalar. **Con una excepción: la primera vez que actualices desde
un APK anterior al build 49 hay que desinstalar** — y ahí sí perdés la configuración
guardada (puerto, switches). De ahí en adelante, nunca más.

El motivo: hasta ese build cada APK se firmaba con una clave distinta. Gradle firma los
builds de debug con un keystore que genera solo si no existe, y CI corre en una máquina
nueva cada vez, así que generaba uno nuevo en cada corrida. Android **se niega a actualizar
una app cuya firma cambió** y el instalador te lo muestra como un escueto "aplicación no
instalada", sin decir por qué. Ahora el repo tiene un keystore fijo
(`app/oshun-debug.keystore`), commiteado a propósito y con la contraseña a la vista: es la
clave de una app de debug que se instala a mano, no protege nada, y lo que compra es que una
actualización entre encima de la anterior y te respete la configuración.

## Cómo funciona

```
Teléfono (esta app)                         Tablet (Navionics)
  lee GPS (1 Hz)                              Paired Devices:
  → arma NMEA ($GPRMC/$GPGGA)   ─ USB/Wi‑Fi ─▶    IP = <ip del telefono>
  → TCP :2000  y/o  UDP :2000                   Port = 2000
                                                Protocol = TCP o UDP
```

La tablet se conecta por **cable USB** o al **hotspot del teléfono**. La app **no arranca** sobre una Wi‑Fi
ajena, y eso es a propósito: ver [Requisito de red](#requisito-de-red-cable-primero-hotspot-si-no-hay-cable).

## Requisito de red: un enlace que zarpe con vos (hotspot o cable)

El puente **solo transmite sobre un enlace que zarpa con vos**, y hay dos. **Cualquiera de
los dos sirve igual**: por el enlace que elijas va todo — la posición y los targets AIS
viajan por la misma conexión, así que nada anda por uno y no por el otro.

1. **Hotspot del teléfono**, y para eso hacen falta las dos condiciones:
   - ✓ **Hotspot del teléfono encendido**
   - ✓ **Wi‑Fi del teléfono apagado**
2. **Cable USB entre el teléfono y la tablet** (anclaje por USB). Alcanza por sí solo, con
   la Wi‑Fi prendida o apagada.

Mientras no se cumpla ninguna de las dos opciones, el botón Iniciar queda deshabilitado.

El motivo es una falla real: en la amarra, el Wi‑Fi del club llega a los dos aparatos, el
emparejamiento funciona y todo parece andar — hasta que zarpás y a los pocos metros el
teléfono se va de esa red. La IP que la app había mostrado deja de significar nada, la
tablet pierde al teléfono y la carta se congela sin que ninguno de los dos avise. El hotspot
y el cable son los únicos enlaces que zarpan con vos.

Además, la IP que muestra la app es **la de un enlace que zarpa con vos**, no la de
cualquier interfaz que aparezca primero: antes podía mostrarte la de la Wi‑Fi (o incluso una
de la red celular) mientras la tablet estaba del otro lado. La tarjeta de estado dice cuál
está usando (`Enlace: hotspot del teléfono` / `cable USB`).

**Si los dos enlaces están arriba** —por ejemplo el anclaje por USB prendido mientras la
tablet está en el hotspot— sólo se puede anunciar una dirección, así que se anuncia la del
cable. Pero la tarjeta de estado lista **las otras direcciones**, porque emparejar contra la
que no es se ve exactamente igual que un puente roto.

### Qué aporta el cable, cuando anda

Navionics lee NMEA por TCP/UDP, o sea que necesita una **red IP**: no existe entrada serie
por USB. El anclaje por USB es justamente eso — el cable se presenta como una placa de red
virtual (`rndis0`/`ncm0`, típicamente `192.168.42.129` del lado del teléfono) — así que para
el puente es una red más, y la mejor de todas: no tiene alcance que perder, no se le mete
nadie, no hay que emparejar nada y el teléfono se va cargando en vez de gastarse la batería
en dos radios.

**No hace falta**: el hotspot hace exactamente el mismo trabajo, y es el camino probado. El
cable es una opción más, para cuando la preferís.

**La salvedad honesta:** que funcione depende de la tablet, no de la app. La tablet tiene que
poder actuar de *host* USB y levantar la interfaz del teléfono; algunas lo hacen y otras
directamente no la ven. Se prueba en treinta segundos: cable puesto → en el teléfono,
Ajustes → Anclaje → **Anclaje por USB** → si la app pasa a mostrar `Enlace: cable USB` y una
IP `192.168.42.x`, andá con cable. Si no aparece, es la tablet: usá el hotspot, que sigue
funcionando exactamente igual que antes.

Un detalle práctico: con cable C‑a‑C los dos aparatos negocian quién es host y no siempre te
toca el que querés. Si no arranca, probá dar vuelta el cable o usar un adaptador OTG del lado
de la tablet. Y si el teléfono no te deja prender el anclaje por USB sin datos móviles
activos, eso es del ROM, no de la app.

**Única excepción: el modo prueba.** Con el barco simulado encendido alcanza con que los
dos aparatos estén en la misma red (la de tu casa, por ejemplo), porque esa corrida no sale
del escritorio. En navegación real el simulador está apagado, así que ahí la regla sigue
siendo dura.

> Si algún día navegás con un router propio a bordo, esta restricción te va a estorbar:
> avisame y agregamos una excepción explícita para ese caso.

## Dos transportes (elegís cuál usar)

La app puede emitir por los dos a la vez; después probás cuál te funciona mejor:

| Transporte | Cómo lo usa Navionics | Notas |
|-----------|------------------------|-------|
| **TCP** | Paired Devices → Protocol **TCP**, host = IP del teléfono, port = 2000 | Servidor; 1 cliente a la vez es lo típico. El más fiable. |
| **UDP** | Paired Devices → Protocol **UDP**, port = 2000 | Broadcast a la subred; varios clientes. Puede fallar con *AP isolation*. |

## Emparejar en la tablet

1. Enlazá los aparatos, de una de estas dos maneras:
   - **Cable USB** (preferido): cable entre teléfono y tablet, y en el teléfono
     Ajustes → Anclaje → **Anclaje por USB**.
   - **Hotspot**: prendé el hotspot del teléfono, **apagá su Wi‑Fi** y conectá la tablet a ese hotspot.
2. Abrí la app en el teléfono, elegí TCP/UDP y puerto, y tocá **Iniciar**. Anotá la **IP** que muestra
   (la del enlace que eligió; la tarjeta de estado te dice cuál es).
3. En Navionics: **Menú → Paired Devices → Add device manually**.
4. Ingresá **Host = IP del teléfono**, **Port = 2000**, **Protocol = TCP** (o UDP) y guardá.
5. La posición del teléfono aparece y se mueve en la carta.

## Modo prueba: barco simulado en el Río de la Plata

Para probar la integración completa con Navionics **sin salir al agua**. Con el switch
**Transmitir barco simulado** (en la tarjeta *Modo prueba*, debajo del botón Iniciar), el
puente deja de leer el GPS del teléfono y transmite un barco que navega entre dos
waypoints fijos en el medio del estuario:

| | Latitud | Longitud |
|---|---|---|
| **A** | 34°57,000′ S | 057°33,000′ W |
| **B** | 35°02,985′ S | 057°20,314′ W |

Están **12,0 M** separados sobre un eje 120°/300°, y ambos en agua abierta: a 8–11 M de la
costa argentina y más de 23 M de la uruguaya. A **4 nudos**, cada tramo dura 3 horas y el
ciclo completo (ida y vuelta) 6 horas; el barco va y viene indefinidamente. En la carta lo
vas a ver moverse a ~120 m por minuto.

**Lo que sale por la red es idéntico a una navegación real** — y es a propósito: si
marcáramos las sentencias como simuladas no estaríamos probando el mismo camino. El aviso
está donde lo ve la persona, no la tablet:

- La tarjeta de estado dice **"MODO PRUEBA: la posición es simulada, no es la tuya"**.
- La notificación del servicio cambia a **"Oshun — MODO PRUEBA (posición simulada)"**.
- El registro abre la sesión con un evento de modo prueba, y el CSV la marca con
  `source=simulated`.

### Barcos AIS de prueba

En modo prueba el puente además transmite **dos barcos AIS**, para poder ver si Navionics
dibuja los targets sin depender de que haya tráfico real cerca:

| MMSI | Nombre | Velocidad | Qué hace |
|---|---|---|---|
| 701999001 | TEST CARGO | 12 nudos | Cruza el rumbo del barco **en escuadra**, por el medio del tramo |
| 701999002 | TEST LANCHA | 3 nudos | Da vueltas cerca del waypoint A, en estado *dedicado a la pesca* |

Van por el **mismo socket** que tu posición, que es como los toma un plotter: la posición
de cada uno como mensaje **tipo 1** (`!AIVDM`) cada 5 segundos, y el nombre como mensaje
**tipo 24 parte A** cada minuto. Un transponder real repite el nombre cada 6 minutos; un
minuto es mejor para probar, y cuando un cliente se conecta los dos mensajes salen enseguida
en vez de dejarte mirando un triángulo sin etiqueta.

Para verlos hay que habilitarlos en la app: **Menu → Map Options → AIS Settings → Display
AIS Targets**.

Las sentencias son **idénticas a las de un AIS real** (nada en el aire dice "simulado"), así
que lo único que impide confundirlos es que se llaman `TEST` y que sus MMSI están en un
bloque que ninguna administración asigna. Fuera del modo prueba **no se transmite ningún
target**, y eso tiene su propio test.

## Barcos AIS de internet

Además de tu posición, el puente puede mostrar en la carta **los barcos que un servicio de
internet reporta cerca tuyo**. Está apagado por defecto y hay que darle una API key.

1. Sacá una key gratis en [aisstream.io](https://aisstream.io).
2. En la app, tarjeta **Barcos AIS (internet)**: pegá la key y prendé el switch.
3. En Navionics: **Menu → Map Options → AIS Settings → Display AIS Targets**.

Los targets van por **la misma conexión que tu posición**, así que funciona igual por hotspot
o por cable. La key queda guardada sólo en el teléfono, en su propio almacenamiento: nunca
entra en la configuración que viaja por intents, ni en el CSV, ni en el registro.

### Lo que hace para no mentirte

Un target viejo dibujado como si fuera actual es peor que no tener nada: pone un barco donde
no hay ninguno, y el triángulo no dice nada de su edad. Entonces:

- Un reporte tiene **vencimiento (6 minutos)**. Pasado eso se descarta, no se vuelve a dibujar.
- Sólo salen los que están **a menos de 12 M**, los más cercanos primero, hasta 40.
- **Si el feed se corta**, la app lo anota en el registro y deja de mandar targets, en vez de
  dejarte fantasmas en la carta.
- Las señales de "no disponible" del estándar (102,3 nudos, rumbo 360, heading 511) y la
  posición 0,0 se descartan en vez de dibujarse.

### Lo que no es

**No sirve para evitar colisiones.** Los datos llegan con demora, dependen de que haya señal,
y sólo incluyen barcos que **transmiten AIS**: lanchas, pescadores y casi todo lo chico no
está. Una carta que se ve vacía porque el feed es pobre es lo más peligroso que esta función
puede producir, y por eso el aviso está en la app y no sólo acá.

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

## Registro de posiciones

La app guarda **qué mandó y qué pasó con cada envío**, que es lo que después permite
distinguir "se cortó el GPS" de "se cortó la red" de "mandé y la tablet no lo consumía".

En **Ver registro** (botón debajo de Iniciar) ves los *eventos*: solo los momentos en que
algo cambió — sesión iniciada, cliente conectado con su IP, entrega trabada, fix viejo,
apagado automático. Un log que repite "OK" una vez por segundo no se lee; lo que importa
es *cuándo* cambió algo.

En paralelo, con el switch **Registrar cada posición en un CSV** (encendido por defecto),
cada emisión se escribe en un CSV rotativo que sobrevive a que el sistema mate el proceso:

```
utc,lat,lon,sog_kn,cog_deg,fix,sats,transports,clients,outcome
2026-08-19T21:00:00Z,-34.601234,-58.381234,10.0,84.4,A,8,TCP,1,OK
```

Se comparte desde la misma pantalla (**Compartir CSV**), que arma un archivo único con el
histórico completo.

### Qué puede y qué no puede saber el puente

TCP **no** confirma que Navionics haya leído ni interpretado una sentencia: no existe
acuse de recibo a nivel de aplicación. Lo que sí sabemos, y queda en la columna
`outcome`, es:

| Estado | Qué significa |
|---|---|
| `OK` | Había un cliente y se llevó el lote completo: lo más cerca de "entregado" que llega TCP |
| `NO_CLIENT` | Nadie conectado: las sentencias no salieron del teléfono |
| `STALLED` | El buffer del socket se llena: escribimos y del otro lado **no lo consumen** |
| `DROPPED` | La conexión se cortó durante el envío |
| `BLIND` | Salió por UDP: sin confirmación posible, por diseño del protocolo |
| `NOT_SENT` | Ningún transporte activo (por ejemplo, el puerto estaba ocupado) |

`STALLED` es el que responde tu pregunta original: el socket sigue abierto, la app cree
que hay un cliente, y sin embargo la carta está congelada. Se detecta porque los sockets
son **no bloqueantes**: una escritura informa cuántos bytes aceptó la ventana del peer, y
una escritura parcial significa que la tablet dejó de leer. Con IO bloqueante eso era
invisible: el `write` simplemente esperaba.

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
    core/Geo.kt                distancias, rumbos e interpolación sobre esfera (millas náuticas)
    core/TrackSimulator.kt     barco simulado entre dos waypoints del Río de la Plata (puro)
    core/DeliveryOutcome.kt    qué pasó con cada envío (entregado / sin cliente / trabado / ciego)
    core/DeliveryTracker.kt    convierte el flujo de envíos en los pocos eventos que importan
    core/EventLog.kt           historial acotado de eventos, observable por la pantalla de registro
    core/TrackLogFormatter.kt  arma el CSV de la bitácora (puro, testeado línea por línea)
    store/ConfigStore.kt       persistencia (SharedPreferences) de config y último motivo de apagado [Android]
    store/TrackLogWriter.kt    CSV rotativo en disco + copia compartible [Android]
    LogActivity.kt             pantalla de registro (Jetpack Compose) [Android]
    location/LocationSource.kt FusedLocationProvider → Fix (Flow) [Android]
    location/SimulatedFixProvider.kt  el track simulado como fuente de fixes, para el modo prueba
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
   Cobertura del core (instrucciones): **97%** — el gate falla el build si baja del 90%.

2. **Unitarios de la app (Robolectric)** — `GpsBridgeServiceTest` arranca el
   foreground service con un GPS falso y verifica el envío de NMEA por socket real:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

3. **Instrumentados (emulador)** — `MainActivityTest` (Compose) corre en un emulador:
   ```bash
   ./gradlew connectedDebugAndroidTest
   ```

**Cobertura mergeada del módulo app** (unit + instrumentado), medida por CI en cada
push: **81% instrucciones / 84% líneas**. El task `jacocoMergedReport` une las dos
ejecuciones, y para que ese número signifique algo el job del emulador corre también
los unitarios antes de generarlo (los dos jobs viven en runners distintos, así que
de lo contrario el reporte "mergeado" solo veía la mitad). Cada push imprime los
porcentajes en el log y sube los reportes HTML como artifacts
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
- [x] Cobertura del core 97% con gate del 90% en CI; app mergeada 81% instrucciones / 84% líneas
- [x] Foreground service + UI
- [x] Intervalo de actualización del GPS configurable (0.5 / 1 / 2 / 5 s)
- [x] Lectura de consumo de batería (nivel %, mA instantáneos, caída %/h)
- [x] Wake lock + Wi-Fi lock, heartbeat de reenvío y detección de cliente TCP muerto
- [x] Config persistida entre reinicios del servicio + apagado automático configurable
- [x] Diagnóstico en pantalla: edad del último fix, del último envío y motivo del último apagado
- [x] Registro de sesión en la app + CSV rotativo de cada posición, con el resultado de cada envío
- [x] Sockets no bloqueantes: se distingue "no había nadie" de "mandé y no lo consumieron"
- [x] Modo prueba: barco simulado a 4 nudos entre dos waypoints a 12 M, para probar Navionics en seco
- [x] Requisito de enlace: no se puede transmitir sobre una Wi‑Fi ajena, y la IP mostrada es la del enlace elegido
- [x] Cable USB (anclaje por USB) como enlace alternativo al hotspot; los dos hacen lo mismo
- [x] Barcos AIS: dos simulados en modo prueba, y un feed real de internet con vencimiento y aviso
- [x] La app muestra su build (versión + commit) y enlaza a la última versión publicada
- [x] Código en inglés; todos los textos de UI en `res/values/strings.xml`
- [x] CI que compila el APK y lo publica como artifact
- [ ] Prueba de campo real contra Navionics (pendiente de hardware)
- [ ] Fuente iOS (fase 2)
