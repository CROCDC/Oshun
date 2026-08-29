# Fuentes de datos AIS para Oshun

Investigación para decidir de dónde salen los barcos que el puente le pasa a Navionics.
**27/08/2026**, cuarta ronda.

> **Historial.** (1) Descarté lo pago con una cuenta viciada — polling cada 30 s. (2) Lo que
> decide no es el precio sino la unidad de cobro. (3) Hay un tercer modelo: suscripción por
> área. (4) **Esta ronda:** hay una fuente oficial argentina, y el teléfono puede ser el
> receptor.

---

## Resumen: hay cuatro caminos, no dos

| Camino | Qué es | Costo | Anda sin internet |
|---|---|---|---|
| **A. Internet gratis** | aisstream (implementado) | $0 | ❌ |
| **B. Internet pago** | VesselAPI, Data Docked, feeds crudos | USD 8 → cotización | ❌ |
| **C. Oficial argentina** | Servidor Nacional AIS de Prefectura | Gratis, con registro | ❌ |
| **D. Receptor propio** | **el mismo teléfono + un dongle** | **USD ~40** una vez | ✅ |

**El camino D es nuevo en esta ronda y probablemente sea el mejor.** Ver §5.

---

## 1. Los tres modelos comerciales

| Modelo | Quién | Escala con… | Para nosotros |
|---|---|---|---|
| **Por posición / barco devuelto** | VesselFinder *Vessels*, VT Explorer *Vessels*, Datalastic | frecuencia **×** barcos | 💀 El Mitre está lleno |
| **Por llamada** | VesselAPI, Data Docked | frecuencia | ✅ Barato y predecible |
| **Suscripción por área** | VT Explorer *LiveData*, VesselFinder *raw NMEA* | tamaño de área y densidad (tarifa fija) | ⭐ Calce perfecto, precio a cotizar |

### El calce perfecto que probablemente no podamos pagar

**VesselFinder vende un feed crudo por TCP/UDP filtrable por área**, en NMEA conforme a la
especificación AIS — `!AIVDM` tal como sale de un receptor. Con eso, la app **no necesitaría
parser ni encoder**: retransmitiría bytes. VT Explorer ofrece lo mismo con *LiveData*.

Ambos cotizan a pedido según área, estaciones y densidad. Es tarifa comercial, pero preguntar
es gratis.

---

## 2. Patrones de consulta

**El radio es la palanca más fuerte:** los barcos crecen con el **cuadrado** del radio. De 12 a
6 millas el costo cae **a la cuarta parte**, y a 6 M un buque a 20 nudos todavía da **18
minutos** de aviso. Después: sólo mientras se transmite (ya es el diseño), la cadencia (cada 5
min en vez de 30 s = 10× más barato), sólo en movimiento, y el piso: un botón manual.

Salida de 6 h, suponiendo ~20 barcos en 12 M y ~6 en 6 M (**estimación mía**, no medida):

| Patrón | Consultas | Por barco (€0,033) | Data Docked (10 cr/llamada) | VesselAPI (1 llamada) |
|---|---|---|---|---|
| Continuo 30 s, 12 M | 720 | **€475** 💀 | 7.200 cr | 720 |
| Cada 5 min, 12 M | 72 | **€47,5** | 720 cr | 72 |
| **Cada 5 min, 6 M** | 72 | **€14,3** | 720 cr | 72 |
| Manual, 10 consultas | 10 | **€6,6** | 100 cr | 10 |

Salidas por mes: **Data Docked** (€80, 6.000 cr) ~8 automáticas / 60 manuales · **VesselAPI
free** (150 llamadas) 2 automáticas / 15 manuales, **gratis** · **Datalastic** (€199) ~46 con
radio 6 M.

---

## 3. Proveedores de internet

| Fuente | Cobra | Precio | Área | Streaming | Veredicto |
|---|---|---|---|---|---|
| **aisstream.io** | nada | **Gratis** | ✅ bbox | ✅ WebSocket | **Implementado.** Cobertura a verificar |
| **VesselAPI** | por llamada | Gratis 150/mes · desde **USD 7,99** | ✅ bbox 4° / radio 100 km | ❌ | **Mejor candidato pago** |
| **Data Docked** | **10 cr/llamada** (confirmado) | Gratis ~100 cr · **€80/mes** | ✅ radio ≤50 km | ❌ | Viable, caro al lado del anterior |
| **VesselFinder** *raw NMEA* | suscripción de área | Cotización | ✅ | ✅ TCP/UDP | ⭐ Ideal. Preguntar |
| **VT Explorer** *LiveData* | suscripción de área | Cotización | ✅ | ❌ | Equivalente |
| **Datalastic** | por barco | €199/mes · ilimitado €679 | ✅ | ❌ | El modelo juega en contra |
| **VesselFinder / VT Explorer** *Vessels* | por posición | €330 / 10.000 cr | ✅ | ❌ | Sólo manual |
| **AISHub** | aportar receptor | "Gratis" | ✅ | ❌ | Exige montar estación |
| **aisfriends** | ¿? | Gratis | parcial | HTTP stream | Orientado a quien aporta estaciones |
| **MarineTraffic** · **FleetMon** | enterprise | **Sin tarifa pública** | ✅ | ❌ | Descartados |

**Nota de mercado:** Kpler compró **MarineTraffic** *y* **FleetMon**. Esa consolidación explica
por qué desaparecieron las tarifas públicas: los dos nombres más conocidos dejaron de vender a
usuarios chicos.

### Gobiernos: gratis, pero de otro hemisferio

Noruega (**BarentsWatch/Kystverket**) y Finlandia (**Digitraffic**) publican AIS abierto y
gratuito con API. Cobertura: sus propias costas. **Ninguno sirve acá** — pero confirma que el
modelo existe, lo que lleva a la pregunta siguiente.

---

## 4. La fuente oficial argentina

**Prefectura Naval opera un Servidor Nacional AIS** (`ais.prefecturanaval.gob.ar`): tráfico
**marítimo y fluvial** en tiempo real, alimentado por una red de **20 estaciones receptoras**
sobre el litoral y la Antártida, e integrado con LRIT, SICAP, la red **CAMAS del Mercosur** y
AIS satelital.

- **Es gratis** para usuarios registrados.
- El registro es para **personas físicas (DNI) vinculadas a la actividad marítima, fluvial y
  portuaria**. Con título náutico y una embarcación matriculada, calificás en principio —
  vale la pena intentarlo.
- **Casi con certeza es un visor web, no una API.** No encontré mención de feed NMEA ni de
  endpoints. Y las condiciones dicen que el servicio puede cambiar sin aviso.

**Para qué sirve igual:** es **la verificación independiente de la cobertura**. Si el visor de
Prefectura muestra doce buques en el Mitre y la app te muestra cero, el problema es la red de
aisstream y no el río. Eso convierte una sospecha en un diagnóstico.

**Lo que no hay que hacer:** scrapearlo. Es un sistema del Estado con condiciones de uso
explícitas y usuarios registrados con DNI. Si querés datos, el camino es preguntarles si
publican un feed.

---

## 5. El teléfono como receptor AIS ⭐

El hallazgo más fuerte de esta ronda. **AIS-catcher for Android** convierte tu Android en un
**receptor AIS de doble canal** con un dongle **RTL-SDR** y un cable **OTG**. Sin drivers
extra, y —lo que importa— **funciona sin internet**. Saca **NMEA por UDP/TCP/HTTP**, que es
exactamente lo que hablamos.

| | |
|---|---|
| **Costo** | Dongle RTL-SDR **USD ~30–40** + antena VHF + cable OTG |
| **Internet** | **No hace falta** |
| **Demora** | Cero: es la señal de VHF directa |
| **Ve** | Todo lo que transmita AIS a la vista, incluido lo que ninguna estación captó |
| **Contra** | Ocupa el puerto USB → adiós cable a la tablet, hay que usar hotspot |
| **Contra** | La app salió del Play Store (regla de datos personales del desarrollador); el APK se baja de su GitHub |
| **Contra** | La antena manda: con una berreta vas a ver poco. Una VHF marina decente cambia todo |

### Lista de hardware, y dónde se equivoca la gente

| Pieza | Qué comprar | La trampa |
|---|---|---|
| **Dongle** | RTL-SDR v3/v4, USD ~30–40 | Cualquiera cubre 162 MHz. AIS-catcher hace **los dos canales** (161,975 y 162,025) con uno solo |
| **Cable / hub** | OTG **con entrada de carga** (PD passthrough) | **Acá se equivoca todo el mundo:** muchos hubs USB-C que dicen "OTG" no tienen Power Delivery. Sin carga, el SDR + GPS + hotspot te funden la batería |
| **Antena** | Para probar, la telescópica del dongle a **46 cm** (¼ de onda a 162 MHz). Para el río, una VHF marina | **Manda la altura, no la ganancia.** Y una VHF marina está sintonizada a ~156 MHz: anda, pero no óptimo a 162 |

Alcance: hasta ~75 km en línea de vista con la antena bien puesta; con la telescópica adentro de
la bañera, mucho menos.

**Dos advertencias que cuestan plata:**

- **No compartas la antena del VHF sin un splitter AIS.** El splitter deja usar una sola antena,
  pero **corta la recepción mientras transmitís** por voz y degrada la recepción el resto del
  tiempo. Una antena propia, aunque sea modesta, suele rendir mejor.
- **OTG y anclaje por USB son excluyentes.** Un puerto no puede ser *host* y *dispositivo* a la
  vez: con el dongle puesto, la tablet va **por hotspot, sí o sí**. Otra razón por la que estuvo
  bien poner los dos enlaces en pie de igualdad.

### Cómo encaja con Oshun

```
RTL-SDR ──USB OTG──▶ AIS-catcher ──UDP a 127.0.0.1──▶ Oshun ──TCP──▶ Navionics
   (VHF)              (mismo teléfono)                  (mezcla con el GPS)
```

AIS-catcher ya sabe mandar NMEA por UDP a apps de plotteo. Nosotros lo recibiríamos en
**localhost**, lo mezclaríamos con nuestro `$GPRMC` y saldría **todo por un solo socket** — que
es justamente lo que Navionics necesita, porque empareja **un** dispositivo.

Eso implica una feature concreta y chica: **entrada NMEA externa por UDP** — el plan para
hacerlo con código propio, sin depender de AIS-catcher, está en
[`plan-ais-propio.md`](plan-ais-propio.md). Y es la misma pieza
que haría falta para el feed crudo de VesselFinder (§1), así que construir una deja la otra
casi hecha.

### Antes de que yo escriba una línea: probá si Navionics acepta dos fuentes

AIS-catcher ya puede mandar NMEA por UDP a una app de plotteo. **Si Navionics admite dos
dispositivos emparejados simultáneos** —AIS-catcher para los barcos, Oshun para tu posición—
entonces **no hace falta escribir nada**: el multiplexado sobra.

Se prueba gratis y hoy: agregá un segundo *paired device* en Navionics y fijate si convive con
el primero o si el segundo pisa al primero. Mi sospecha es que un plotter toma una sola fuente
—de ahí el diseño multiplexor— pero **no lo verifiqué**, y verificarlo puede ahorrar la feature
entera.

**Por qué esto le gana a cualquier API:** no depende de que haya señal, ni de que una red
voluntaria tenga un receptor cerca, ni de que un proveedor siga existiendo. Y cuesta una vez.

---

## 6. Medir la cobertura **hoy, sin salir al agua**

### Un dato del río que mejora el pronóstico

**El Canal Mitre corre siempre a menos de 6 millas de la costa.** Eso importa: el AIS terrestre
depende de estaciones en tierra con alcance de hasta ~200 km, así que la pregunta deja de ser
"¿llega la cobertura hasta el canal?" —llega de sobra— y pasa a ser sólo "¿esta red tiene una
estación en el área de Buenos Aires?". Para un puerto de ese tamaño es mucho más probable que
sí. No es certeza, pero **mueve la apuesta bastante a favor de la opción gratis**.

Corolario práctico: **un radio de 6 millas ya cubre todo el ancho del canal alrededor tuyo.** Si
alguna vez pasamos a un proveedor que cobra por barco, ése es el número que hay que usar.

### El experimento

La suscripción es un cuadro de **~18 millas alrededor del teléfono** y se transmiten los
targets de menos de 12 M. Desde Buenos Aires o el conurbano ribereño **eso ya cubre la rada y
el canal**.

Prendé **Barcos AIS (internet)** con la key, modo prueba apagado, transmitiendo. Mirá el
registro (**"Feed AIS conectado"**) y después **`Targets AIS: N`** en la tarjeta de estado. No
hace falta Navionics ni el barco. Si en la rada —donde siempre hay buques fondeados— da 0 con
el feed conectado, esa red no sirve acá, y lo sabés en diez minutos.

Cruzalo con el visor de Prefectura (§4) y tenés diagnóstico, no sospecha.

---

## 7. Demora, y una consecuencia de diseño anotada

- **Streaming** (aisstream, feed crudo, receptor propio): segundos, o cero.
- **REST**: cada consulta devuelve *la última posición conocida*, que ya puede tener minutos.
  Con consultas cada 5 min, la edad en pantalla es **la demora del proveedor + hasta 5 minutos
  nuestros**. Se suman.

**Consecuencia para el código:** hoy sellamos cada target con **nuestro** reloj, correcto para
un stream — el mensaje acaba de llegar. Con REST sería **una mentira**: un dato de hace 8
minutos entraría como recién nacido y el vencimiento de 6 minutos nunca lo descartaría. Si
migramos a REST, la marca de tiempo tiene que venir **del proveedor**.

---

## 8. Recomendación

1. **Medí aisstream desde tierra esta semana.** Diez minutos, gratis, contesta la pregunta.
2. **Registrate en el Servidor Nacional AIS de Prefectura.** Gratis, y te da la vara contra la
   cual comparar.
3. **Si aisstream cubre el Mitre:** quedate. Gratis, streaming, ya hecho.
4. **Si no cubre pero hay tráfico:** probá **VesselAPI free** (150 llamadas), y si convence,
   USD 7,99/mes con radio 6 M, cada 5 min, sólo transmitiendo y en movimiento.
5. **Si querés que ande sin internet —que es cuando el AIS más importa— comprá el dongle
   RTL-SDR (USD ~40) y una antena decente.** Es la respuesta estructural, cuesta menos que dos
   meses de cualquier API, y me toca agregar la entrada NMEA por UDP.
6. **Preguntá el precio del feed crudo** de VesselFinder/VT Explorer. Gratis preguntar.
7. **Descartados:** MarineTraffic y FleetMon (Kpler, sin tarifa pública), Datalastic (€199 y
   cobra por barco), AISHub y aisfriends (exigen aportar estación).

### Qué habría que construir

- **Para un proveedor REST:** cliente + parser nuevos y el cambio de marca de tiempo de §7. Un
  día. El modelo `AisTarget`, la tabla `AisTraffic` con vencimiento y el encoder `!AIVDM` se
  reusan tal cual.
- **Para el receptor propio (o el feed crudo):** entrada NMEA externa por UDP y multiplexado.
  Más simple todavía: las sentencias ya vienen armadas, **ni siquiera se decodifican**.

---

## 9. Qué no pude verificar

El proxy de este entorno bloquea `aisstream.io`, `datalastic.com`, `datadocked.com`,
`vesselapi.com` y `argentina.gob.ar`, así que **todo salió de resultados de búsqueda, no de las
páginas de los proveedores**. Queda abierto:

- La **cobertura real** de cualquiera de estas redes en el Río de la Plata. Se mide con la app.
- Si Prefectura da **algún acceso de datos** o sólo el visor, y si un deportista puede registrarse.
- Las **cuotas** de los planes pagos de VesselAPI (confirmé el free de 150/mes y el piso de USD 7,99).
- El **precio** de las suscripciones por área de VesselFinder y VT Explorer.
- Qué antena hace falta para que el RTL-SDR rinda en el río.
- Todos los precios: se mueven, y varios proveedores dejaron de publicarlos.

---

## Fuentes (27/08/2026)

**Internet:** [aisstream](https://aisstream.io/coverage) · [VesselAPI](https://vesselapi.com/pricing) · [Data Docked](https://datadocked.com/pricing) ([área](https://datadocked.com/vessels-by-area-api)) · [Datalastic](https://datalastic.com/pricing/) · [VesselFinder NMEA crudo](https://www.vesselfinder.com/realtime-ais-data) · [VT Explorer LiveData](https://api.vtexplorer.com/docs/livedata.html) · [AISHub](https://www.aishub.net/join-us) · [aisfriends](https://www.aisfriends.com/docs/api/v1) · [MarineTraffic](https://help.marinetraffic.com/hc/en-us/articles/205115108-Set-up-your-API-Services) · [FleetMon](https://datarade.ai/data-providers/fleetmon/profile)

**Oficiales:** [PNA — Servidor Nacional AIS](https://www.argentina.gob.ar/prefecturanaval/ais) · [Condiciones](https://ais.prefecturanaval.gob.ar/condiciones) · [Red de 20 estaciones](https://www.argentina.gob.ar/noticias/una-red-de-20-estaciones-receptoras-del-sistema-de-identificacion-automatica-monitorean) · [Kystverket](https://www.kystverket.no/en/sea-transport-and-ports/ais/access-to-ais-data/) · [Digitraffic](https://www.digitraffic.fi/en/marine-traffic/ais/)

**Receptor propio:** [AIS-catcher for Android](https://github.com/jvde-github/AIS-catcher-for-Android) · [AIS-catcher](https://github.com/jvde-github/AIS-catcher) · [RTL-SDR: decoder AIS para Android](https://www.rtl-sdr.com/a-new-ais-decoder-for-the-rtl-sdr-on-android/) · [AIS + OpenCPN en un velero](https://www.rtl-sdr.com/using-ais-share-opencpn-and-an-rtl-sdr-on-a-sailboat/) · [Guía de armado](https://www.worldwideais.org/post/how-to-set-up-sdr-ais-receiver-ais-catcher)
