# Fuentes de datos AIS: qué hay, qué cuesta, qué sirve para Oshun

Evaluación de las APIs de AIS posibles para el puente, escrita el **27/08/2026**.

Los precios de estos servicios cambian y varios ya no publican tarifa: **verificá antes de
pagar**. Lo que está marcado como *verificado hoy* salió de una búsqueda de esa fecha; lo que
está marcado como *sin verificar* es conocimiento previo o inferencia, y no lo tomes como dato
duro.

---

## 1. La pregunta que este documento **no** puede contestar

**¿Tiene aisstream.io buena cobertura del Canal Mitre?**

No lo sé, y desde el entorno donde se desarrolla esta app no puedo averiguarlo: el proxy
bloquea el acceso a `aisstream.io`, así que no pude abrir su mapa de cobertura ni conectarme
al feed. Lo que sí se puede decir:

- El AIS terrestre depende de **receptores voluntarios en tierra**, con alcance típico de
  hasta ~200 km en línea de vista ([aisstream](https://aisstream.io/coverage)).
- El Mitre es el canal de acceso a Buenos Aires y al Paraná: rodeado de puertos grandes
  (Buenos Aires, Dock Sud, La Plata, Campana, Zárate, Montevideo) y con **VTS Río de la Plata
  operando 24 h** ([Prefectura](https://www.argentina.gob.ar/prefecturanaval/avisos/rio_de_la_plata)).
  Es la clase de zona donde *suele* haber receptores.
- Pero "hay receptores en la zona" y "**esta red en particular** tiene uno" son cosas
  distintas. Ninguna de estas redes publica cobertura garantizada por región.

### Cómo medirlo de verdad, en 20 minutos

Ya está todo construido para eso. No hace falta creerle a nadie:

1. Prendé **Barcos AIS (internet)** con la key, modo prueba apagado, y arrancá la transmisión.
2. Mirá el registro: si dice **"Feed AIS conectado"**, el problema no es la conexión.
3. Mirá la tarjeta de estado: **`Targets AIS: N`**. Ese número *es* la cobertura, medida donde
   estás parado.
4. Repetilo en la amarra y, si podés, una vez navegando. El Mitre a la vista debería darte
   varios buques; si da 0 con el feed conectado, la cobertura no sirve y hay que mirar otra
   fuente.

Ese experimento cuesta cero y contesta la pregunta mejor que cualquier página de marketing.

---

## 2. Qué necesita *esta* app (y por qué descarta a casi todos los pagos)

No estamos siguiendo una flota de barcos conocidos. Necesitamos **todo lo que haya en un
cuadrado de agua alrededor nuestro, de forma continua**. Los requisitos duros:

| Requisito | Por qué |
|---|---|
| **Suscripción por zona** (bounding box), no por MMSI | No sabemos de antemano qué barcos hay cerca |
| **Streaming**, no polling | Un target sirve si es reciente; consultar cada 30 s por REST multiplica el costo |
| **Costo plano o gratis** | Es una app personal, no un negocio |
| Poco tráfico y tolerante a cortes | Corre en un teléfono, con datos móviles, en el agua |
| Posición + rumbo + velocidad + nombre | Es lo que dibuja el plotter |

**El punto clave del análisis:** casi todas las APIs pagas están tarifadas para *seguimiento
de flota* — pagás por posición consultada, por barco conocido. Nuestro caso es el opuesto, y
en ese modelo se vuelve caro rápido. `aisstream` no es sólo la más barata: su forma
(WebSocket + bounding box) es **exactamente** la que necesitamos.

---

## 3. Comparación

| Fuente | Modelo | Costo | Por zona | Streaming | Veredicto para Oshun |
|---|---|---|---|---|---|
| **aisstream.io** | WebSocket público | **Gratis** | ✅ | ✅ | **Lo que está implementado.** La forma correcta; la cobertura es la incógnita |
| **AISHub** | Cooperativa | Gratis, *pero* hay que aportar un receptor propio | ✅ (por área) | ❌ (HTTP) | Bloqueado: exige montar una estación receptora |
| **VesselFinder** | Créditos | Desde **€330** / 10.000 créditos | ✅ (endpoint de área) | ❌ (REST) | Caro para uso continuo; 1 crédito por posición terrestre |
| **Datalastic** | Suscripción | ~**€99–199 / mes** | ✅ | ❌ (REST) | Precio de empresa para un uso personal |
| **MarineTraffic** (Kpler) | Enterprise | **Sin tarifa pública** | ✅ | ❌ | Hay que hablar con ventas; señal de que no es para este tamaño |
| **Spire / satelital** | Enterprise | Miles de USD | ✅ | ✅ | Fuera de escala. Además el AIS satelital llega con demora |
| **Receptor físico** | Hardware propio | **USD ~100–300, una vez** | n/a | n/a (VHF directo) | **La única opción que funciona sin internet.** Ver §5 |

---

## 4. Fichas

### aisstream.io — *lo que ya está integrado*

- **Verificado hoy:** gratis, WebSocket (`wss://stream.aisstream.io/v0/stream`), key por
  registro con mail, suscripción por *bounding boxes*. Agrega una red de estaciones
  terrestres; ellos mismos aclaran que **no dan cobertura global del 100%** y que no reciben
  barcos a cientos de kilómetros de la costa.
- **A favor:** la forma exacta que necesitamos, costo cero, ya implementado y testeado.
- **En contra:** cobertura no garantizada ni contractual; si su red no tiene receptor cerca
  del Mitre, no hay plan B dentro del servicio. Servicio comunitario, sin SLA.
- **Riesgo real:** que un día deje de existir. Mitigación: el parser está aislado, cambiar de
  fuente es reescribir una clase.

### AISHub — *gratis con letra chica*

- **Verificado hoy:** feed agregado de 1.500+ estaciones en 80 países, con web service
  XML/JSON/CSV. **No hay plan pago:** las credenciales se ganan aportando un feed NMEA crudo
  desde tu propio receptor por UDP, y hay umbrales de calidad — cobertura de al menos 10
  barcos, 90% de uptime en 7 días y demoras bajo 10 segundos.
- **Veredicto:** inviable como atajo. Sólo tiene sentido si además montás la estación
  receptora — y si montás una estación, ya tenés AIS local y no necesitás la API.

### VesselFinder — *el pago más accesible*

- **Verificado hoy:** sistema de créditos que vencen a los 12 meses; **1 crédito por posición
  terrestre y 10 por posición satelital**; entrada desde **€330 por 10.000 créditos**. Tiene
  endpoint de *posiciones en un área personalizada*.
- **La cuenta que importa:** 20 barcos en el área, refrescados cada 30 s, son ~57.600
  posiciones por día. A 1 crédito cada una, los 10.000 créditos se agotan **en horas**. El
  modelo no está pensado para esto.

### Datalastic — *suscripción plana*

- **Verificado hoy:** planes con crédito mensual, del orden de **€99–199/mes** según el plan,
  con prueba de 14 días y 10% de descuento anual.
- **Veredicto:** es el más honesto de los pagos para uso continuo, pero €100+/mes para ver
  barcos en un velero personal es desproporcionado.

### MarineTraffic (Kpler) — *ya no juega en esta liga*

- **Verificado hoy:** eliminaron el sistema de créditos en enero de 2025; ahora hay tiers
  Basic/Essential/Enterprise **sin tarifa pública ni alta self-service**, tras la compra por
  Kpler. Los planes web baratos (USD 10–75/mes) son para el sitio, **no** dan API.
- **Veredicto:** descartado sin siquiera pedir presupuesto.

---

## 5. La opción que no es una API: un receptor AIS propio

Vale la pena tenerla escrita, porque **resuelve el problema que ninguna API resuelve**.

Un receptor AIS (dAISy, Quark-elec, Digital Yacht y similares, **USD ~100–300**) escucha el
VHF directo:

- **Sin internet.** Funciona donde no hay señal, que es justo donde el AIS importa más.
- **Tiempo real.** Sin la demora de una red de agregación.
- **Ve todo lo que transmite cerca tuyo**, incluidos los que ninguna estación terrestre captó.

El puente ya está en condiciones de multiplexarlo: leería `!AIVDM` del receptor y lo mandaría
por el mismo socket que la posición, exactamente como hace hoy con los targets simulados y con
los de internet. Sería pasar de "fuente de posición" a **multiplexor NMEA**, que es lo que hace
un gateway comercial de USD 200.

El detalle a resolver: el teléfono tiene un solo puerto USB, así que un receptor USB compite
con el cable a la tablet. Habría que ver uno con Wi-Fi, o volver a hotspot.

---

## 6. Recomendación

1. **Medí primero.** Probá aisstream en el agua con el build actual. Es gratis, ya está hecho,
   y contesta la única pregunta que importa.
2. **Si la cobertura sirve:** quedate ahí. Ninguna paga te da algo mejor para este caso, y
   varias te dan lo mismo peor y cobrando.
3. **Si la cobertura no sirve:** no saltes a una API paga — el problema sería la falta de
   receptores en la zona, y **las pagas se nutren de las mismas redes terrestres**. Salteá
   directo al **receptor físico**, que es la respuesta estructural.
4. **Lo único que justificaría pagar** es querer ver barcos *fuera* del alcance VHF (tráfico
   lejano, planificación). Eso no es seguridad, es curiosidad — y a ese precio, cara.

---

## Fuentes consultadas (27/08/2026)

- [aisstream.io — Our AIS Message Coverage](https://aisstream.io/coverage)
- [AISHub — Join us](https://www.aishub.net/join-us) · [AISHub — API](https://www.aishub.net/api)
- [VesselFinder — Vessel Positions API](https://www.vesselfinder.com/vessel-positions-api) · [Custom area API](https://www.vesselfinder.com/vessel-positions-custom-area-api)
- [Datalastic — Pricing](https://datalastic.com/pricing/)
- [MarineTraffic — Set up your API services](https://help.marinetraffic.com/hc/en-us/articles/205115108-Set-up-your-API-Services)
- [Data Docked — comparación de proveedores de AIS](https://datadocked.com/ais-api-providers)
- [Prefectura Naval — Río de la Plata](https://www.argentina.gob.ar/prefecturanaval/avisos/rio_de_la_plata)
