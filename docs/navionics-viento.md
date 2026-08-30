# ¿Navionics recibe datos de viento?

Investigación para contestar si tiene sentido que el puente mande viento por el mismo socket
que la posición. **30/08/2026.**

---

## Respuesta corta

**No.** Navionics Boating no muestra viento de un dispositivo emparejado. Del socket NMEA toma
tres cosas —**posición, profundidad y AIS**— y descarta el resto, viento incluido. Mandar
`MWV` por el puerto 2000 sería gastar bytes: no rompe nada y no aparece en ningún lado.

El viento **que sí ves** en la app no viene del barco: viene de **internet** (capa de
meteorología y boyas). Son dos caminos distintos que no se tocan.

---

## 1. Qué consume Navionics del dispositivo emparejado

| Sentencia | Qué es | ¿La usa? |
|---|---|---|
| `RMC` | posición, hora, SOG, COG | ✅ GPS externo (es la que emite Oshun) |
| `GGA`, `GLL`, `VTG`, `ZDA` | posición / rumbo y velocidad / hora | ✅ alternativas a `RMC` |
| `DPT`, `DBT` | profundidad | ✅ es lo que alimenta **SonarChart Live** |
| `!AIVDM` / `!AIVDO` | targets AIS | ✅ desde que existe la función AIS |
| **`MWV`, `MWD`, `VWR`, `VWT`** | **viento aparente / real** | ❌ **no** |
| `HDG`, `VHW`, `MTW`, motores, baterías | heading, velocidad por el agua, temperatura del agua… | ❌ / sin confirmar |

La lista de las que sí entran está publicada por terceros que integran contra la app (Sonar
Server, Yacht Devices, Digital Yacht), no por Navionics: **la app no publica una spec de qué
sentencias parsea.** Ver §5.

**El resumen que repiten los integradores y los foros es siempre el mismo:** la app lee GPS y
profundidad (y AIS), y el resto de los instrumentos —viento el primero— no aparece. Digital
Yacht lo dice en su guía de configuración de apps; en los foros de vela es un reclamo viejo:
llegan COG, SOG y posición, y nada más.

---

## 2. El viento de la app viene de internet, no del barco

Navionics tiene **capa de meteorología**: pronóstico de viento (flechas/barbas sobre la carta,
por hora), más **boyas** con observaciones. Eso es un servicio online de Garmin/Navionics.

Consecuencias, que son las que importan a bordo:

- **Necesita datos en la tablet.** Si la tablet está colgada del hotspot del teléfono y el
  teléfono se queda sin señal, no hay viento. Es la misma dependencia que ya tiene el feed AIS
  de internet (ver [`docs/ais-fuentes.md`](ais-fuentes.md)).
- **Es pronóstico, no medición.** Es una grilla de modelo interpolada a tu posición, no lo que
  sopla en tu mástil. Sirve para planificar la salida, no para trimar.
- **No pasa por el puerto 2000.** Nada de lo que haga Oshun lo mejora ni lo empeora.

---

## 3. Qué significa para Oshun

Tres razones, y con una sola alcanzaba:

1. **El teléfono no tiene anemómetro.** No hay dato propio que transmitir. Los sensores del
   celular no miden viento y no hay manera de inferirlo desde el GPS: lo que se puede calcular
   es el **rumbo y velocidad sobre el fondo**, que ya sale en `RMC`, y que mezcla viento con
   corriente. En el Mitre, con la corriente que hay, esa diferencia no es un detalle.
2. **Aunque tuviéramos el dato, Navionics lo tira.** Si algún día hay un instrumento de viento
   a bordo con salida NMEA, el puente podría multiplexarlo (es el mismo trabajo que el AIS,
   ver [`docs/plan-ais-propio.md`](plan-ais-propio.md))… y la tablet lo ignoraría igual.
3. **Emitir pronóstico como si fuera medición sería mentir.** Bajar viento de una API y
   mandarlo en `MWV` lo haría indistinguible de un sensor real. Es exactamente lo que la app
   evita en todo lo demás: un fix viejo sale marcado inválido (`RMC` status `V`), los targets
   AIS vencen a los 6 minutos, y el modo prueba se anuncia en tres lugares. No vamos a romper
   esa regla para un dato que además nadie va a leer.

**Conclusión: no se implementa.** Ni `MWV` en el stream, ni viento de internet reenviado como
NMEA.

---

## 4. Si igual querés viento a bordo

| Camino | Qué da | Costo |
|---|---|---|
| **La capa de meteo de Navionics** | pronóstico sobre la carta, ya lo tenés | incluido en el plan; necesita internet |
| **App aparte en pantalla dividida** (NMEAremote, dashboards NMEA) | instrumentos reales, si hay instrumentos | requiere hardware a bordo |
| **Pantalla propia en Oshun** (Open-Meteo / SMN) | pronóstico de viento en el teléfono, junto a la posición y la batería | trabajo nuestro; **no toca el stream NMEA** |

El tercero es el único que nos toca a nosotros, y es una función **de la app**, no del puente:
viento en la pantalla del teléfono, claramente rotulado como pronóstico, sin ensuciar lo que
sale por el socket. Si te interesa, se discute aparte.

---

## 5. Cuánto confiar en esto, y cómo verificarlo en diez minutos

**Confianza: alta, pero es evidencia indirecta.** No hay documento oficial de Navionics que
liste las sentencias que parsea; lo que hay es la documentación de los fabricantes de gateways
y años de reportes coincidentes. Nadie publica "no soportamos MWV": lo que se ve es que nadie
lo consiguió.

**El experimento que lo cierra, sin salir al agua** —y lo puede correr cualquiera con la tablet
en la mesa:

1. Modo prueba encendido, tablet emparejada como siempre.
2. Agregar al stream, a mano o con una rama descartable, una `MWV` válida junto a las de
   siempre. Por ejemplo `$WIMWV,045.0,R,12.5,N,A*14` (viento aparente de 45° a 12,5 nudos), repetida por segundo.
3. Mirar la app: barra de datos, capas, cualquier lugar donde pudiera mostrarse.

Si aparece, esta nota está equivocada y lo corregimos. Si no aparece —lo esperable— queda
verificado contra la app real y no contra foros. **La prueba de control es la profundidad**:
mandar también una `DPT` (`$SDDPT,12.4,0.5,*49`) y ver que *ésa* sí se refleja confirma que la tablet está leyendo el
socket y que el silencio del viento es de Navionics, no del enlace.

---

## Fuentes

- [Sonar Server — NMEA Sentences Received by Navionics App](https://sonarserver.com/us/2015/03/30/nmea-sentences-received-by-navionics-app/) — la lista: `DBT`, `DPT`, `GGA`, `GLL`, `RMC`, `VTG`, `ZDA`.
- [Yacht Devices — Using Navionics Boating App with NMEA Wi‑Fi Gateway](https://www.yachtd.com/news/navonics_app_sonarchart_live.html)
- [Digital Yacht — How to configure Apps & Software](https://digitalyacht.support/tutorials/how-to-configure-apps-software/)
- [PredictWind — DataHub conectando por Wi‑Fi a Navionics / Aquamaps / OpenCPN](https://help.predictwind.com/en/articles/8332728-datahub-connecting-by-wi-fi-to-navionics-aquamaps-or-opencpn-to-receive-nmea-data)
- [Garmin — Navionics Boating: How Does the Wind Forecast Feature Work?](https://support.garmin.com/en-US/?faq=lgv58z4sFl1OZFZdFRgR9A)
- [Navionics — Weather & Tides](https://www.navionics.com/features/weather-tides)
- [YBW Forum — NMEA data on iPad Navionics chart](https://forums.ybw.com/threads/nmea-data-on-ipad-navionics-chart.505161/) y [Cruisers & Sailing Forums — Navionics shows depth but AIS data not working](https://www.cruisersforum.com/forums/f121/navionics-shows-depth-but-ais-data-not-working-yacht-devices-wifi-nmea-and-matsutec-268700.html)
