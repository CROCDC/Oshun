# Fuentes de datos AIS: qué hay, cómo cobran, y qué patrón las hace viables

Evaluación para el puente Oshun. **27/08/2026**, segunda ronda de investigación.

> **Historial de correcciones.** La primera versión descartó lo pago con una cuenta viciada
> (asumía *polling* cada 30 s). La segunda encontró que lo que decide es la unidad de cobro.
> Esta tercera agrega dos hallazgos que vuelven a mover la conclusión: un modelo comercial que
> no había mirado, y la forma de medir la cobertura **sin salir al agua**.

---

## 1. Los tres modelos comerciales

No son dos, son tres. Y el tercero es el que mejor encaja.

| Modelo | Quién | El costo escala con… | Para nosotros |
|---|---|---|---|
| **Por posición / por barco devuelto** | VesselFinder *Vessels*, VT Explorer *Vessels*, Datalastic | frecuencia **×** barcos | 💀 Peor caso: el Mitre está lleno |
| **Por llamada, cuota mensual** | VesselAPI, Data Docked | frecuencia, nada más | ✅ Barato y predecible |
| **Suscripción por área** | VT Explorer *LiveData*, VesselFinder *raw NMEA* | tamaño del área y densidad de tráfico (tarifa fija) | ⭐ El calce perfecto — precio por cotización |
| *(Sin modelo)* | aisstream | nada | ✅ Gratis |

### El calce perfecto, que probablemente no podamos pagar

**VesselFinder vende un feed AIS crudo por TCP/UDP**, filtrable **por área**, con *downsampling*
para bajar el ancho de banda, en formato NMEA conforme a la especificación AIS — o sea
`!AIVDM` tal cual sale de un receptor.

Si tuviéramos eso, la app **no necesitaría ni el parser ni el encoder**: sería *retransmitir los
bytes* a Navionics. Todo el trabajo de decodificar JSON y volver a armar los bits binarios
—que es la parte más delicada de lo que ya construimos— se volvería innecesario.

VT Explorer ofrece lo equivalente con su *LiveData API*: suscripción de tarifa fija según
tamaño de área y densidad.

**La contra:** ambos cotizan a pedido, según área, cantidad de estaciones y densidad de
tráfico. Es tarifa comercial, casi seguro fuera de escala para un velero. Pero **pedir la
cotización es gratis**, y si el número es razonable es la mejor arquitectura posible.

---

## 2. Patrones de consulta, ordenados por potencia

**El radio es la palanca más fuerte** (y la menos obvia): los barcos crecen con el *área*, o
sea con el **cuadrado** del radio. Bajar de 12 a 6 millas no baja el costo a la mitad, lo baja
**a la cuarta parte**. Y a 6 M un buque a 20 nudos todavía da **18 minutos** de aviso: para
conciencia situacional, que es lo único que este feed puede dar honestamente, sobra.

Después: **sólo mientras se transmite** (ya es el diseño), **la cadencia** (lineal: cada 5 min
en vez de cada 30 s es 10× más barato), **sólo en movimiento** (fondeado la foto no cambia) y
el piso de todo, un **botón manual "traer barcos ahora"**.

### La cuenta, salida de 6 horas

Supuestos: ~20 barcos dentro de 12 M en el Mitre, ~6 dentro de 6 M. **Estimaciones mías**, no
medidas — el número real lo vas a ver en la app.

| Patrón | Consultas | Por barco (VesselFinder, €0,033) | Por llamada (Data Docked, 10 cr) | Por llamada (VesselAPI) |
|---|---|---|---|---|
| Continuo 30 s, 12 M | 720 | 14.400 cr → **€475** 💀 | 7.200 cr | 720 |
| Cada 2 min, 12 M | 180 | 3.600 cr → **€119** | 1.800 cr | 180 |
| Cada 5 min, 12 M | 72 | 1.440 cr → **€47,5** | 720 cr | 72 |
| **Cada 5 min, 6 M** | 72 | 432 cr → **€14,3** | 720 cr | 72 |
| Manual, 10 consultas | 10 | 200 cr → **€6,6** | 100 cr | 10 |

Traducido a salidas por mes:

- **Data Docked Deckhand (€80/mes, 6.000 cr):** ~**8 salidas** cada 5 min · **60** en manual.
- **VesselAPI free (150 llamadas/mes):** **2 salidas** cada 5 min · **15** en manual — **gratis**.
- **Datalastic Starter (€199/mes, 20.000 cr):** ~46 salidas con radio 6 M · pero pagando €199.

Con cobro por llamada, **bajar el radio ya no ahorra plata** — sólo baja el ruido en la carta.
El tráfico del canal deja de ser un problema económico.

---

## 3. Los proveedores

| Fuente | Cobra | Precio | Área | Streaming | Veredicto |
|---|---|---|---|---|---|
| **aisstream.io** | nada | **Gratis** | ✅ bbox | ✅ WebSocket | **Implementado.** Cobertura a verificar |
| **VesselAPI** | por llamada | Gratis 150/mes · desde **USD 7,99** | ✅ bbox 4° / radio 100 km | ❌ REST + webhooks | **Mejor candidato pago.** El free tier alcanza para probarlo |
| **Data Docked** | **10 cr por llamada de área** ✅ confirmado | Gratis ~100 cr · **€80/mes** 6.000 cr | ✅ radio ≤50 km | ❌ REST | Viable. Caro al lado de VesselAPI |
| **VesselFinder** *raw NMEA* | suscripción por área | **Cotización** | ✅ | ✅ TCP/UDP | ⭐ Arquitectura ideal. Pedir precio |
| **VT Explorer** *LiveData* | suscripción por área | **Cotización** | ✅ | ❌ | Equivalente al anterior |
| **Datalastic** | por barco devuelto | €9 trial · **€199/mes** · ilimitado €679 | ✅ | ❌ | El modelo juega en contra |
| **VesselFinder / VT Explorer** *Vessels* | por posición | **€330** / 10.000 cr (12 meses) | ✅ | ❌ | Sólo patrón manual |
| **AISHub** | aportar receptor propio | "Gratis" | ✅ | ❌ | Bloqueado en la práctica |
| **MarineTraffic** (Kpler) | enterprise | **Sin tarifa pública** | ✅ | ❌ | Descartado |
| **Receptor físico** | hardware | **USD 100–300** una vez | n/a | VHF directo | **La única sin internet.** Ver §6 |

Notas: Data Docked cobra **10 créditos por request** en *Vessels by Area*, **plano, sin importar
cuántos barcos vuelvan** — eso lo saca de la lista negra. Datalastic, en cambio, descuenta según
la cantidad de barcos encontrados (tope 500). VesselFinder y VT Explorer comparten modelo de
créditos (1 terrestre / 10 satelital) y ambos venden aparte una suscripción de área.

---

## 4. Cobertura: se puede medir **hoy, sin salir al agua**

**¿Tiene aisstream cobertura del Canal Mitre?** Sigo sin saberlo: el proxy de este entorno
bloquea `aisstream.io`, `datalastic.com`, `datadocked.com` y `vesselapi.com`, así que **todo
este documento sale de resultados de búsqueda, no de las páginas de los proveedores**.

Pero hay una forma de contestarlo sin mover el barco. **La suscripción es un cuadro de ~18
millas alrededor del teléfono** (12 de alcance + 6 de margen), y los targets que se transmiten
son los de menos de 12 M. Desde Buenos Aires o el conurbano ribereño, ese cuadro **ya cubre la
rada y el canal**.

O sea: prendé el feed **en tierra**, cerca del río, y mirá `Targets AIS: N` en la tarjeta de
estado. No hace falta Navionics ni salir a navegar. Si en la rada de Buenos Aires —donde
siempre hay buques fondeados— el contador da 0 con el feed conectado, esa red no sirve acá, y
lo sabés en diez minutos desde el auto.

Pasos: prendé **Barcos AIS (internet)** con la key, modo prueba apagado, transmitiendo. Mirá el
registro (**"Feed AIS conectado"**) y después el contador. Repetirlo navegando confirma, pero
la primera lectura ya te dice casi todo.

---

## 5. Latencia, y una consecuencia de diseño que hay que anotar

La demora importa más que el precio para el encuadre de seguridad, y **los modelos no son
iguales**:

- **Streaming** (aisstream, feed crudo): llega cuando llega el mensaje. La demora es la de la
  red de agregación, del orden de segundos.
- **REST**: cada consulta devuelve *la última posición conocida*, que ya puede tener minutos.
  Con consultas cada 5 minutos, la edad del dato en pantalla es **la demora del proveedor más
  hasta 5 minutos nuestros**. Se suman.

**Consecuencia concreta para el código:** hoy sellamos cada target con **nuestro** reloj
(`reportedAtMillis = ahora`), que es lo correcto para un stream — el mensaje acaba de llegar.
Con un proveedor REST eso sería **una mentira**: un dato de hace 8 minutos entraría como
recién nacido y el vencimiento de 6 minutos nunca lo descartaría. Si alguna vez migramos a
REST, hay que tomar la marca de tiempo **del proveedor** y no la nuestra. Está anotado acá
para que no se pierda.

---

## 6. La opción que no es una API: receptor AIS propio

Resuelve el problema que **ninguna** API resuelve. Un receptor (dAISy, Quark-elec, Digital
Yacht; **USD 100–300**) escucha el VHF directo: **sin internet**, en **tiempo real**, y **ve
todo lo que transmite cerca tuyo** — incluido lo que ninguna estación terrestre captó.

El puente ya está en forma de multiplexarlo: leería `!AIVDM` del receptor y lo mandaría por el
mismo socket que la posición, igual que hoy con los simulados y los de internet. Y es
exactamente la misma arquitectura que necesitaría el feed crudo de VesselFinder (§1), así que
construir una de las dos deja la otra casi hecha.

Pendiente: el teléfono tiene un solo puerto USB, así que un receptor USB compite con el cable a
la tablet. Habría que ver uno con Wi-Fi, o volver a hotspot.

---

## 7. Recomendación

1. **Medí aisstream desde tierra, esta semana.** Diez minutos, gratis, y contesta la única
   pregunta abierta.
2. **Si la cobertura sirve:** quedate. Es gratis, es streaming y ya está hecho.
3. **Si falla pero hay tráfico en la zona:** probá **VesselAPI con su free tier** (150 llamadas
   alcanzan para dos salidas). Si convence, USD 7,99/mes con el patrón: **radio 6 M, cada 5
   minutos, sólo transmitiendo, sólo en movimiento**, más botón manual.
4. **Pedí la cotización del feed crudo de VesselFinder / VT Explorer.** Es gratis preguntar y
   es la mejor arquitectura posible. Si vuelve con un número de tres cifras mensuales, cerrás
   el mail y listo.
5. **Si no hay cobertura terrestre en la zona:** ninguna API te salva — **receptor físico**.
6. **Descartados:** MarineTraffic (sin tarifa pública), Datalastic (€199 y cobra por barco),
   AISHub (exige montar estación).

### Qué habría que construir para un proveedor REST

La mitad cara ya está hecha y **no depende de la fuente**: el modelo `AisTarget`, la tabla con
vencimiento y filtro por distancia (`AisTraffic`) y el encoder a `!AIVDM`. Migrar es **un
cliente nuevo, un parser nuevo y el cambio de marca de tiempo de §5**: un día de trabajo sin
tocar lo probado.

---

## 8. Qué no pude verificar

- **La cobertura real** de cualquiera de estas redes en el Río de la Plata. Se mide con la app.
- Las **cuotas exactas** de los planes pagos de VesselAPI (sólo confirmé el free de 150/mes y
  el piso de USD 7,99).
- El **precio** de las suscripciones por área de VesselFinder y VT Explorer: sólo por cotización.
- Si Datalastic mantiene el plan Experimenter (80.000 créditos) y a qué precio.
- Todos los precios: se mueven, y varios proveedores dejaron de publicarlos.

---

## Fuentes (27/08/2026)

- [aisstream.io — Coverage](https://aisstream.io/coverage)
- [VesselAPI — Pricing](https://vesselapi.com/pricing) · [AIS Data API](https://vesselapi.com/ais-data-api) · [Docs](https://vesselapi.com/docs)
- [Data Docked — Pricing](https://datadocked.com/pricing) · [Vessels by Area](https://datadocked.com/vessels-by-area-api) · [API Reference](https://datadocked.com/api-reference)
- [Datalastic — Pricing](https://datalastic.com/pricing/) · [API Reference](https://datalastic.com/api-reference/)
- [VesselFinder — Real-time AIS data (NMEA/TCP)](https://www.vesselfinder.com/realtime-ais-data) · [Área personalizada](https://www.vesselfinder.com/vessel-positions-custom-area-api) · [Vessel Positions API](https://www.vesselfinder.com/vessel-positions-api)
- [VT Explorer — AIS Data API](https://www.vtexplorer.com/ais-data-en/) · [LiveData (área)](https://api.vtexplorer.com/docs/livedata.html)
- [AISHub — Join us](https://www.aishub.net/join-us) · [API](https://www.aishub.net/api)
- [MarineTraffic — Set up your API services](https://help.marinetraffic.com/hc/en-us/articles/205115108-Set-up-your-API-Services)
- [Prefectura Naval — Río de la Plata](https://www.argentina.gob.ar/prefecturanaval/avisos/rio_de_la_plata)
