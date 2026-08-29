# Plan: recibir AIS por radio, con código nuestro

Plan de trabajo, **no** implementación. Escrito el 27/08/2026.

**Objetivo:** que el teléfono reciba AIS del VHF con un dongle SDR y **código nuestro**, sin
depender de AIS-catcher, y **sin dejar de compartir el GPS** — todo por el mismo socket que
Navionics ya escucha.

---

## 1. Principios

1. **Una sola salida.** Navionics empareja un dispositivo; el puente mezcla posición propia y
   tráfico ajeno en un stream. Esto ya es como está construido hoy.
2. **Nada de dependencias en el camino crítico.** Si el AIS se cae, el GPS sigue. Si el SDR se
   desconecta, el GPS sigue. Nunca al revés.
3. **Cada fase entrega algo usable**, y ninguna tira lo de la anterior.
4. **Lo puro se testea; lo que toca hardware se aísla.** Igual que hicimos con el resto: el
   demodulador es matemática pura sobre un array de muestras, y por lo tanto testeable con
   grabaciones. El acceso USB es pegamento, y va aparte.

---

## 2. Arquitectura objetivo

```
                    ┌──────────── el mismo teléfono ────────────┐
   antena VHF       │                                            │
   (tope de palo)   │   ┌─────────┐   I/Q    ┌──────────────┐   │
        │           │   │  USB    │─────────▶│ demodulador  │   │
        └──coax─────┼──▶│ RTL2832 │  240 kHz │ AIS (nuestro)│   │
                    │   └─────────┘          └──────┬───────┘   │
                    │                               │ !AIVDM     │
                    │   ┌─────────┐  $GPRMC/$GPGGA  ▼            │
                    │   │   GPS   │────────▶ ┌─────────────┐     │
                    │   └─────────┘          │ multiplexor │     │
                    │                        └──────┬──────┘     │
                    │   (entrada NMEA externa ──────┘            │
                    │    UDP/TCP: receptor real, AIS-catcher)    │
                    └───────────────────┬────────────────────────┘
                                        │ TCP :2000
                                        ▼
                                    Navionics
```

Las tres fuentes de AIS —feed de internet, entrada NMEA externa, demodulador propio— entran al
**mismo multiplexor** y salen por el **mismo socket**. Elegir una u otra es configuración, no
arquitectura.

---

## 3. Fases

### Fase 0 — Dos experimentos gratis, antes de escribir o comprar nada

| Experimento | Cómo | Qué decide |
|---|---|---|
| **¿Navionics acepta dos fuentes?** | Agregar un segundo *paired device* y ver si convive | Si acepta, buena parte del multiplexado sobra |
| **¿aisstream cubre el Mitre?** | Prender el feed desde tierra y mirar `Targets AIS: N` | Si cubre, el SDR pasa de necesidad a mejora |

Ninguno cuesta plata ni código.

### Fase 1 — Entrada NMEA externa (UDP y TCP) 🔑

**La pieza que no se tira nunca.** Un puerto local donde el puente escucha sentencias NMEA ya
armadas y las mezcla en la salida.

Sirve, sin cambiar una línea, para:
- **AIS-catcher hoy** (manda NMEA por UDP) — o sea que tenés AIS por radio *antes* de que yo
  escriba el demodulador.
- **Un receptor AIS real mañana** (dAISy, Quark-elec, Digital Yacht): todos sacan NMEA por
  serie, USB o Wi-Fi.
- **Un feed crudo comercial**, si alguna vez se paga.
- **Nuestro propio demodulador**, que en el fondo es una fuente más.

Trabajo: escuchar un socket, validar el checksum, descartar lo que no es AIVDM/AIVDO, meterlo
en el multiplexor. **Chico.** Es la fase con mejor relación valor/esfuerzo de todo el plan.

### Fase 2 — Política de mezcla

Qué pasa cuando hay más de una fuente a la vez, que es el caso realista (internet en el canal,
radio en todos lados).

- **Prioridad:** la radio le gana a internet para el mismo MMSI — es más nueva y no miente.
- **Dedupe por MMSI**, que la tabla `AisTraffic` ya hace.
- **Vencimiento por fuente:** un target de radio caduca distinto que uno de internet.
- **Marca de tiempo de la fuente, no nuestra** (el problema anotado en `ais-fuentes.md` §7).
- **Contadores por fuente** en la tarjeta de estado: cuántos targets vienen de radio y cuántos
  de internet. Sin eso no se puede diagnosticar nada.

Buena parte ya existe; es sobre todo agregar el campo "fuente" y las reglas.

### Fase 3 — Demodulador AIS propio

Acá está el trabajo de verdad. Se divide en dos mitades muy distintas.

#### 3a. Acceso al dongle (pegamento, no testeable)

Leer I/Q del RTL2832U por USB desde Android: `UsbManager`, permisos, transferencias *bulk*
asíncronas, e inicializar el sintonizador (R820T2) con su secuencia de registros.

**Escalón intermedio recomendado:** existe un driver de RTL2832U para Android que expone
`rtl_tcp` en localhost. Nos conectamos por TCP y recibimos I/Q **sin escribir una línea de
USB**. Eso permite hacer toda la Fase 3b —que es la parte interesante y difícil— y dejar el
driver USB propio para después, o nunca.

⚠️ **Decisión de licencia pendiente.** `librtlsdr` y los drivers de Android derivados son
**GPL**. Portar sus secuencias de inicialización puede obligarnos a licenciar la app como GPL.
Hoy **este repo no tiene archivo de licencia**, así que hay que decidirlo antes, no después.
Usar `rtl_tcp` en un proceso aparte evita el problema, que es exactamente por qué ese patrón
existe.

#### 3b. La cadena DSP (pura, testeable, la parte linda)

AIS es **GMSK a 9600 bps** en dos canales: **161,975** (A) y **162,025 MHz** (B), separados
25 kHz.

```
sintonizar 162,000 MHz @ 240 kSps
   ├── mezclar −25 kHz → filtro pasabajos ~12 kHz → diezmar ×5 → 48 kHz  → canal A
   └── mezclar +25 kHz → filtro pasabajos ~12 kHz → diezmar ×5 → 48 kHz  → canal B

por canal:  discriminador FM → filtro adaptado → recuperación de reloj (5 muestras/símbolo)
         → NRZI → desentramado HDLC (flag 0x7E, quita del bit de relleno) → CRC-16
         → bits → payload de 6 bits → !AIVDM
```

**Dos decisiones de diseño que hacen esto viable en Kotlin:**

1. **240 kSps, no 1,5 Msps.** El RTL2832U admite 225–300 kSps, y los dos canales entran de
   sobra en 240 kHz. Son **480 kB/s** en vez de 3 MB/s: la diferencia entre "hay que bajar a
   C con el NDK" y "un hilo en Kotlin sin asignar memoria alcanza".
2. **48 kHz = 5 muestras por símbolo**, que es el número clásico para recuperar el reloj sin
   sobrar trabajo.

**Y lo mejor: el último paso ya está hecho.** El `BitWriter` del `AisEncoder` que escribimos
para los targets simulados hace exactamente el armado de 6 bits que necesita la salida. La
parte más delicada de la cadena ya está escrita y testeada, sólo que en el otro sentido.

**Cómo se testea sin barco ni antena:** grabando I/Q crudo una vez (o bajando una grabación
pública de AIS) y corriendo el demodulador contra el archivo. Entrada determinística, salida
esperada. Es un test unitario, no una salida al río.

#### 3c. Doble canal y afinado

Los dos canales en paralelo, control de ganancia, y medir cuántos mensajes con CRC válido por
minuto. Ése es el número que dice si el conjunto antena+cable+dongle funciona.

### Fase 4 — Hardware (que en el tiempo va primero, ver §4)

---

## 4. La antena: la inversión que sobrevive a todo

Es lo primero en el tiempo si vas a subir al palo, y **lo único que no se reemplaza con
código**. Sirve igual para el dongle de hoy y para un receptor real mañana.

### Cuánto se gana con la altura

El horizonte de radio, en millas náuticas, con alturas en metros:

**d ≈ 2,2 × (√h₁ + √h₂)**

| Tu antena | Contra un buque (30 m) | Contra una lancha (2 m) |
|---|---|---|
| Tope de palo (**15 m**) | **~21 M** | **~12 M** |
| Balcón de popa (**2 m**) | ~15 M | ~6 M |

El tope de palo te da ~6 millas más contra un buque grande — pero **casi el doble** contra algo
chico. Y lo chico es justamente lo que no ves de noche.

### Qué comprar, en orden de importancia

1. **El cable, no la antena.** A 162 MHz, 20 metros de RG-58 se comen buena parte de la señal.
   Para una tirada al tope: **RG-8X como mínimo, LMR-240 o RG-213 mejor**. Un cable malo
   arruina cualquier antena.
2. **La antena.** Una VHF marina de fibra estándar anda; están sintonizadas cerca de 156 MHz y
   AIS está en 162, así que perdés algo pero funciona. Una **antena dedicada a AIS** (sintonizada
   a 162) rinde más si el palo queda libre.
3. **Conectores.** PL-259/SO-239 arriba; abajo, un *pigtail* a SMA o MCX para el dongle.
   Conectores mal hechos pierden más que 10 metros de cable.

### La antena del VHF **es** una antena de AIS

No son cosas distintas: **es la misma banda**. El VHF marino va de 156 a 162 MHz y el AIS está
en 161,975 / 162,025 — literalmente en el techo de esa banda (canales 87B y 88B).

Están optimizadas cerca de 156 MHz, sí, pero eso pesa **al transmitir**. Para recibir, una
desadaptación que en TX preocuparía cuesta **menos de 1 dB**. O sea: para una prueba de
recepción, la antena del palo es prácticamente ideal, y ya tiene puestos la altura y el cable,
que es lo caro.

### ⚠️ Prueba de concepto con la antena del barco

**El peligro:** si transmitís por el VHF con el dongle conectado a esa antena, **lo quemás en el
acto**. Son 25 W entrando a un receptor diseñado para microvolts. No se degrada: se destruye.

**Por eso, la regla:** desconectá **físicamente** el equipo de VHF del cable. No alcanza con
apagarlo — nadie tiene que poder apretar el PTT mientras el dongle está enchufado.

| | |
|---|---|
| **Qué hacés** | Sacás el coax del VHF y lo enchufás al dongle |
| **Qué necesitás** | Adaptador **PL-259/SO-239 → SMA** (un par de dólares) + el dongle |
| **Qué perdés** | El VHF mientras dure la prueba |
| **Qué ganás** | La respuesta definitiva: antena real, cable real, altura real |

Es **mejor** prueba que cualquier antena improvisada, precisamente porque prueba la instalación
final. Si con la antena del palo no ves barcos, ninguna otra cosa lo va a arreglar. Si con una
telescópica no ves nada, no sabés si fue la antena o la cobertura: resultado inconcluso, que es
el peor de todos.

Dos detalles prácticos:

- **Descargá la estática antes de enchufar.** Una antena en el tope junta carga y el dongle no
  tiene protección: tocá brevemente vivo contra malla del conector antes de conectar.
- **Si tenés antena de emergencia en el balcón de popa, esa es la prueba ideal**: la usás para
  el dongle y dejás el VHF conectado al palo. Sin desconectar nada, sin riesgo.

**El hardware mínimo para la primera prueba es entonces un dongle y un adaptador** — no una
antena nueva.

### El problema real a futuro: el palo ya tiene la antena del VHF

Tres caminos:

| Opción | Costo | Contra |
|---|---|---|
| **Splitter AIS/VHF activo** (Vesper, Digital Yacht, Comar) | USD 150–250 | Corta la recepción AIS mientras transmitís por voz; degrada algo el resto del tiempo. **Es la solución permanente, no la de la prueba** |
| **Segunda antena al tope** | Antena + cable + trabajo | Espacio, peso e interferencia mutua arriba |
| **Antena dedicada más abajo** (cruceta, backstay, balcón) | Lo más barato | Menos alcance — mirá la tabla |

**Mi sugerencia:** si vas a subir al palo igual, poné **la tirada de cable buena ahora** aunque
al principio uses el dongle con una antena modesta. El cable y el paso por el palo son el
trabajo caro; el resto se cambia desde la bañera.

---

## 5. Orden que propongo

| # | Qué | Cuándo | Costo |
|---|---|---|---|
| 1 | Los dos experimentos de Fase 0 | Ahora | $0 |
| 2 | **Fase 1: entrada NMEA externa** | Después de la 0 | Código chico |
| 3 | Dongle + cable OTG con carga + **adaptador PL-259→SMA** (la antena ya la tenés) | Cuando quieras | ~USD 45 |
| 4 | Probar con AIS-catcher entrando por la Fase 1 | Enseguida | $0 |
| 5 | Decidir licencia del repo (§3a) | Antes de la 3 | Una decisión |
| 6 | Fase 3b: demodulador propio contra grabación | El proyecto grande | Tiempo |
| 7 | Fase 3a: driver USB propio, o quedarnos en `rtl_tcp` | Opcional | Tiempo |
| 8 | Antena y cable definitivos al palo | Cuando bajes el palo | USD 150–400 |

El punto 4 es importante: **con la Fase 1 hecha ya tenés AIS por radio funcionando**, usando
AIS-catcher como demodulador provisorio. El demodulador propio deja de ser un bloqueo y pasa a
ser una mejora — que es exactamente donde uno quiere que estén los proyectos grandes.

---

## 6. Riesgos y preguntas abiertas

| Riesgo | Qué tan grave | Mitigación |
|---|---|---|
| **Licencia GPL de los drivers RTL** | Alto si se copia código | Decidir licencia del repo; o `rtl_tcp` en proceso aparte |
| **Rendimiento del DSP en Kotlin** | Medio | 240 kSps lo hace plausible; si no alcanza, NDK sólo para el filtro |
| **Consumo de batería** | Alto en la práctica | Hub con carga; medirlo antes de confiar |
| **Puerto USB ocupado** | Cierto | Con el dongle, la tablet va por hotspot. Ya está resuelto |
| **Antena mal puesta** | Alto y silencioso | Medir mensajes con CRC válido por minuto, no "se ven barcos" |
| **Que el demodulador falle callado** | Alto | Contador de CRC fallidos vs válidos, en la app |

---

## 7. Lo que este plan **no** hace

- **No transmite.** Seguimos invisibles para los demás. Un transponder es otro aparato, otro
  precio y otro trámite ante Prefectura.
- **No reemplaza mirar afuera.** Nada de esto ve lo que no transmite AIS, que en el río es la
  mayoría.
- **No lo empiezo todavía.** Esto es el plan; decime por dónde arrancamos.
