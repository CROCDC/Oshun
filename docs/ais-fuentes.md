# Fuentes de datos AIS: qué hay, cómo cobran, y qué patrón de consulta las hace viables

Evaluación para el puente Oshun. Escrita el **27/08/2026**, revisada el mismo día.

> **Corrección respecto de la primera versión de este documento.** Descarté las opciones pagas
> con una cuenta viciada: asumí *polling* continuo cada 30 segundos, que es el peor caso
> posible y no el que necesitamos. Con un patrón de consulta razonable la ecuación cambia por
> completo, y encima aparece un modelo de cobro que vuelve el tráfico del canal irrelevante.
> Todo el análisis de abajo está rehecho sobre esa base.

---

## 1. Lo que decide de verdad: **cómo cuenta** el proveedor

El precio de lista es la parte menos importante. Lo que define si un servicio es viable para
esta app es **la unidad que factura**, porque nosotros consultamos *un área*, no un barco.

| Modelo de cobro | El costo escala con… | Qué le pasa en el Canal Mitre |
|---|---|---|
| **Por barco devuelto** | frecuencia **×** cantidad de barcos | 💀 Mata: el Mitre está lleno, y cada consulta cuesta más justo donde más barcos hay |
| **Por llamada a la API** | frecuencia, y nada más | ✅ Un canal congestionado sale igual que el mar vacío |
| **Por stream abierto** | nada, es un caño abierto | ✅ Es lo que usa aisstream |

**Éste es el hallazgo del análisis.** VesselFinder y Datalastic cobran *por barco devuelto* —
Datalastic lo dice explícitamente para su endpoint de área: los créditos se descuentan según
la cantidad de barcos encontrados. VesselAPI, en cambio, cuenta **llamadas**. Para nuestro
caso eso es una diferencia de orden de magnitud, no de porcentaje.

---

## 2. Patrones de consulta, y cuánto mueven la aguja

Las palancas, **ordenadas por potencia**:

### 2.1 El radio (la más fuerte, y la menos obvia)

La cantidad de barcos crece con el **área**, o sea con el **cuadrado del radio**. Bajar el
radio de 12 a 6 millas no baja el costo a la mitad: lo baja **a la cuarta parte**.

Y cuesta poco en seguridad: a 6 millas, un buque acercándose a 20 nudos te da **18 minutos**
de aviso. Para conciencia situacional —que es lo único que este feed puede dar honestamente—
alcanza y sobra.

### 2.2 Consultar sólo mientras se transmite

Ya es el diseño actual: el feed se abre con la sesión y se cierra con ella. Fuera del agua no
gasta nada.

### 2.3 La cadencia (lineal, la más fácil de tocar)

Cada 5 minutos en vez de cada 30 segundos es **10× más barato**. Un barco a 15 nudos se corre
1,25 M en 5 minutos: para dibujar tráfico en la carta, sobra.

### 2.4 Sólo cuando el barco se mueve

Fondeado o amarrado, la foto no cambia. Suspender las consultas bajo cierta velocidad es gratis
de implementar y recorta las horas muertas.

### 2.5 Manual: un botón "traer barcos ahora"

El piso absoluto. Diez consultas en toda una salida, cuando vos las pedís. Convierte cualquier
plan pago en algo casi gratis, a cambio de que la carta no se actualice sola.

### 2.6 La cuenta, para una salida de 6 horas

Supuestos: **~20 barcos** dentro de 12 M en el Mitre, **~6 barcos** dentro de 6 M. Son
estimaciones mías, no medidas — el número real lo vas a ver en la app.

| Patrón | Consultas | Cobro **por barco** (VesselFinder, €0,033 c/u) | Cobro **por llamada** (VesselAPI) |
|---|---|---|---|
| Continuo cada 30 s, 12 M | 720 | 14.400 cr → **€475** 💀 | 720 llamadas |
| Cada 2 min, 12 M | 180 | 3.600 cr → **€119** | 180 llamadas |
| Cada 5 min, 12 M | 72 | 1.440 cr → **€47,5** | 72 llamadas |
| **Cada 5 min, 6 M** | 72 | 432 cr → **€14,3** | 72 llamadas |
| Manual, 10 consultas | 10 | 200 cr → **€6,6** | 10 llamadas |

La columna de la derecha es la que importa: **con cobro por llamada, bajar el radio no cambia
nada porque ya no cuesta nada.** El tráfico del canal deja de ser un problema económico.

---

## 3. Los proveedores

| Fuente | Cómo cobra | Precio | Área | Streaming | Veredicto |
|---|---|---|---|---|---|
| **aisstream.io** | nada | **Gratis** | ✅ bounding box | ✅ WebSocket | **Implementado.** Forma ideal; cobertura a verificar |
| **VesselAPI** | **por llamada** | Gratis 150/mes · desde **USD 7,99/mes** | ✅ bbox 4° / radio 100 km | ❌ REST + webhooks | **El mejor candidato pago.** Su unidad de cobro es la correcta |
| **Data Docked** | créditos (unidad sin confirmar) | Gratis 100 cr · **€80/mes** 6.000 cr | ✅ radio ≤50 km | ❌ REST | Posible, si cobra por llamada. **Sin confirmar** |
| **Datalastic** | **por barco devuelto** | Trial €9 · **€199/mes** 20.000 cr · ilimitado €679 | ✅ `vessel_inradius` | ❌ REST | Caro, y el modelo juega en contra |
| **VesselFinder** | **por posición** | **€330** / 10.000 cr (vencen a 12 meses) | ✅ área | ❌ REST | Sólo con patrón manual; si no, se evapora |
| **AISHub** | gratis con contrapartida | Hay que aportar un receptor propio | ✅ | ❌ | Bloqueado: exige montar estación |
| **MarineTraffic** (Kpler) | enterprise | **Sin tarifa pública** | ✅ | ❌ | Descartado |
| **Spire / satelital** | enterprise | Miles de USD | ✅ | ✅ | Fuera de escala, y llega con demora |
| **Receptor físico** | hardware | **USD 100–300**, una vez | n/a | VHF directo | **La única que anda sin internet.** Ver §5 |

### Fichas

**aisstream.io** — Gratis, WebSocket, key por mail, suscripción por *bounding boxes*. Agrega
una red de estaciones terrestres y aclara que **no da cobertura global del 100%**. Sin SLA:
es un servicio comunitario y podría desaparecer. Mitigación ya tomada: el parser está aislado,
cambiar de fuente es reescribir una clase.

**VesselAPI** — Free tier de **150 llamadas/mes** sin tarjeta, planes desde **USD 7,99/mes**,
endpoints de *bounding box* (hasta 4°) y de radio (hasta 100 km), REST + webhooks. Lo
interesante: el free tier ya cubre **2 salidas por mes** consultando cada 5 minutos, y **15
salidas** con el patrón manual. *No pude verificar* la cuota exacta de los planes pagos ni si
los endpoints de área facturan distinto.

**Data Docked** — Deckhand **€80/mes** con 6.000 créditos, Seafarer €150 / 15.000, Navigator
€250 / 30.000; endpoint *Vessels by Area* con radio hasta 50 km y un free tier de ~100
créditos. **La pregunta decisiva —si el crédito es por llamada o por barco— no la pude
responder**, y de eso depende todo: a 6.000 créditos, si cobra por barco son ~4 salidas al mes;
si cobra por llamada, son ochenta.

**Datalastic** — Trial €9; Starter **€199/mes** con 20.000 créditos; ilimitado €679/mes. Su
endpoint de área **descuenta créditos según la cantidad de barcos encontrados** (tope 500 por
consulta). Con radio 6 M y consultas cada 5 min entran ~46 salidas al mes — técnicamente
alcanza, pero estás pagando €199 por lo que otro te da por USD 8.

**VesselFinder** — **€330 por 10.000 créditos**, 1 crédito por posición terrestre y 10 por
satelital, vencen a los 12 meses. Sin suscripción mensual, así que sirve si navegás poco: con
patrón manual son ~50 salidas por esos €330. Con consulta automática se evapora.

**AISHub** — Feed de 1.500+ estaciones en 80 países, XML/JSON/CSV, **sin plan pago**: las
credenciales se ganan aportando tu propio receptor por UDP, con umbrales de calidad (10 barcos
de cobertura, 90% de uptime en 7 días, demoras bajo 10 s). Si montás el receptor ya tenés AIS
local y no necesitás la API.

**MarineTraffic (Kpler)** — Eliminaron los créditos en enero de 2025; hoy es Basic/Essential/
Enterprise **sin tarifa pública ni alta self-service**. Los planes web de USD 10–75/mes son
para el sitio, **no** dan API.

---

## 4. La cobertura: lo único que no se puede investigar desde un escritorio

**¿Tiene aisstream buena cobertura del Canal Mitre?** No lo sé. El proxy de este entorno
bloquea `aisstream.io` (y también `datalastic.com` y `vesselapi.com`), así que ni siquiera pude
abrir sus mapas de cobertura: **todo lo de arriba sale de resultados de búsqueda, no de las
páginas de los proveedores**.

Lo que sí se puede afirmar: el AIS terrestre depende de receptores voluntarios con alcance de
hasta ~200 km, y el Mitre está rodeado de puertos grandes con VTS 24 h. Es la clase de zona
donde *suele* haber receptores — pero "hay receptores" y "**esta red** tiene uno" son cosas
distintas, y ninguna garantiza cobertura por región.

### El experimento que lo contesta, gratis

1. Prendé **Barcos AIS (internet)** con la key, modo prueba apagado, y transmitiendo.
2. Registro: ¿dice **"Feed AIS conectado"**? Entonces la conexión no es el problema.
3. Tarjeta de estado: **`Targets AIS: N`**. Ese número *es* la cobertura donde estás parado.
4. Repetilo en la amarra y navegando. Con el Mitre a la vista deberían aparecer varios buques.

Si el feed conecta y da 0 con tráfico visible, la cobertura no sirve — y **ahí conviene saltar
al receptor físico, no a una API paga**, porque las pagas se nutren de las mismas redes
terrestres.

---

## 5. La opción que no es una API: receptor AIS propio

Resuelve el problema que **ninguna** API resuelve. Un receptor (dAISy, Quark-elec, Digital
Yacht; **USD 100–300**) escucha el VHF directo:

- **Sin internet.** Anda donde no hay señal, que es donde el AIS más importa.
- **Tiempo real**, sin la demora de una red de agregación.
- **Ve todo lo que transmite cerca tuyo**, incluido lo que ninguna estación captó.

El puente ya está en forma de multiplexarlo: leería `!AIVDM` del receptor y lo mandaría por el
mismo socket que la posición, igual que hoy con los targets simulados y los de internet. Pasaría
de "fuente de posición" a **multiplexor NMEA**, que es lo que hace un gateway de USD 200.

Pendiente a resolver: el teléfono tiene un solo puerto USB, así que un receptor USB compite con
el cable a la tablet. Habría que ver uno con Wi-Fi, o volver a hotspot.

---

## 6. Recomendación

1. **Medí aisstream primero.** Es gratis, ya está hecho, y contesta la única pregunta abierta.
2. **Si la cobertura sirve:** quedate. Ninguna paga mejora un stream gratis con la forma correcta.
3. **Si la cobertura falla pero hay barcos en la zona** (o sea: el problema es esa red puntual),
   el candidato es **VesselAPI**, por su unidad de cobro. Su free tier alcanza para probarlo sin
   pagar nada, y ahí sí conviene implementar el patrón: **radio 6 M, cada 5 minutos, sólo
   mientras se transmite y sólo en movimiento**, más un botón manual.
4. **Si no hay cobertura terrestre en la zona**, ninguna API te salva: **receptor físico**.
5. **Descartados por precio o por modelo:** MarineTraffic (sin tarifa pública), Datalastic (€199
   y cobra por barco), VesselFinder (salvo uso manual esporádico), AISHub (exige receptor).

### Lo que habría que construir para soportar un proveedor REST

La mitad cara ya está hecha y es **independiente de la fuente**: el modelo `AisTarget`, la tabla
con vencimiento y filtro por distancia (`AisTraffic`), y el encoder a `!AIVDM`. Cambiar de
aisstream a un REST paginado es escribir **un cliente nuevo y un parser nuevo** — un día de
trabajo, sin tocar nada de lo que ya está probado.

---

## 7. Qué no pude verificar (y habría que confirmar antes de pagar)

- La cobertura real de cualquiera de estas redes en el Río de la Plata.
- Si **Data Docked** cobra por llamada o por barco. Decide si es viable o carísimo.
- Las cuotas exactas de los planes pagos de **VesselAPI**, y si sus endpoints de área facturan
  distinto que el resto.
- Si **Datalastic** mantiene el plan Experimenter (80.000 créditos) y a qué precio.
- Todos los precios: se mueven, y varios proveedores ya dejaron de publicarlos.

---

## Fuentes (27/08/2026)

- [aisstream.io — Coverage](https://aisstream.io/coverage)
- [VesselAPI — Pricing](https://vesselapi.com/pricing) · [AIS Data API](https://vesselapi.com/ais-data-api) · [Docs](https://vesselapi.com/docs)
- [Data Docked — Pricing](https://datadocked.com/pricing) · [Vessels by Area API](https://datadocked.com/vessels-by-area-api) · [Comparativa de proveedores](https://datadocked.com/ais-api-providers)
- [Datalastic — Pricing](https://datalastic.com/pricing/) · [API Reference](https://datalastic.com/api-reference/)
- [VesselFinder — Vessel Positions API](https://www.vesselfinder.com/vessel-positions-api) · [Área personalizada](https://www.vesselfinder.com/vessel-positions-custom-area-api)
- [AISHub — Join us](https://www.aishub.net/join-us) · [API](https://www.aishub.net/api)
- [MarineTraffic — Set up your API services](https://help.marinetraffic.com/hc/en-us/articles/205115108-Set-up-your-API-Services)
- [Prefectura Naval — Río de la Plata](https://www.argentina.gob.ar/prefecturanaval/avisos/rio_de_la_plata)
