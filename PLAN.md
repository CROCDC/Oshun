# Oshun — Compartir GPS del teléfono con Navionics en la tablet

Plan para una app que toma la ubicación GPS del teléfono y la transmite por Wi‑Fi
en formato **NMEA 0183**, de manera que la app **Navionics / Garmin Boating** en la
tablet la reconozca como fuente de posición externa.

---

## 1. Cómo funciona (el hallazgo clave)

Navionics **no** lee la ubicación de otra app del sistema. En cambio, tiene una
función **"Paired Devices" (Dispositivos emparejados)**:

> Menú → Paired Devices → *Add device manually* → se ingresa **IP**, **puerto** y
> **protocolo (TCP o UDP)**.

Ahí Navionics se conecta como **cliente de red** a una fuente que emita sentencias
**NMEA 0183** por Wi‑Fi (es el mismo mecanismo que usan los gateways NMEA de barco
y apps como *NMEA GPS Tether* o *ShareGPS*).

**Entonces la arquitectura es:**

```
┌─────────────────────────┐         Wi‑Fi (misma red / hotspot)        ┌────────────────────────┐
│  TELÉFONO (fuente GPS)   │                                            │  TABLET (Navionics)    │
│                         │   NMEA 0183 por TCP/UDP  (puerto 2000)      │                        │
│  App Oshun:             │  ───────────────────────────────────────▶  │  Paired Devices:       │
│  • Lee GPS (1 Hz)       │   $GPRMC,...  $GPGGA,...                    │  IP=telefono  port=2000 │
│  • Arma sentencias NMEA │                                            │  → posición en la carta │
│  • Servidor TCP/UDP     │                                            │                        │
└─────────────────────────┘                                            └────────────────────────┘
```

Requisito de red: **ambos dispositivos en la misma red Wi‑Fi**, o el teléfono
creando un **hotspot** al que se conecta la tablet.

---

## 2. Decisión de plataforma

| Rol | Plataforma recomendada | Motivo |
|-----|------------------------|--------|
| **Teléfono (fuente)** | **Android (Kotlin)** | Permite servidor de red + GPS en un *foreground service* con la pantalla apagada, sin las restricciones de iOS. |
| **Tablet (destino)** | Cualquiera con Navionics (Android o iOS) | Solo actúa como cliente de red; no requiere nuestra app. |

> **iOS como fuente**: técnicamente posible (abrir un socket TCP y emitir NMEA),
> pero iOS limita el networking + GPS en segundo plano. Si el teléfono fuente es un
> iPhone, se documentará como fase 2. **El MVP asume teléfono Android.**

*(A confirmar: ¿el teléfono fuente es Android o iPhone? El plan MVP asume Android.)*

---

## 3. Qué construimos (MVP)

App Android **"Oshun GPS Bridge"** que:

1. **Pide permisos de ubicación** (`ACCESS_FINE_LOCATION` + `FOREGROUND_SERVICE_LOCATION`).
2. **Obtiene posición** vía `FusedLocationProviderClient` a ~1 Hz (lat, lon, velocidad, rumbo, altitud, hora).
3. **Convierte a NMEA 0183**, generando al menos:
   - `$GPRMC` — mínimo recomendado (hora, lat, lon, velocidad SOG, rumbo COG, fecha).
   - `$GPGGA` — calidad de fix, altitud, nº de satélites.
   - *(opcional)* `$GPVTG`, `$GPGLL`.
   - Cada sentencia con **checksum** (XOR de los caracteres entre `$` y `*`).
4. **Sirve las sentencias por red**:
   - **Servidor TCP** en puerto **2000** (Navionics se conecta como cliente). Es el modo más confiable.
   - *(opcional)* **UDP broadcast** en 2000 para varios clientes a la vez.
5. Corre como **foreground service** con notificación persistente (sigue transmitiendo con la pantalla apagada).
6. **UI mínima**: botón Iniciar/Detener, muestra la **IP local del teléfono** y el **puerto**, estado de conexión y última posición emitida.

### Formato NMEA — ejemplo
```
$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,,*<cs>
$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*<cs>
```

---

## 4. Pasos de implementación

1. **Scaffold** del proyecto Android (Kotlin, Gradle, minSdk 26+).
2. Módulo **`nmea/`**: formateo de sentencias + cálculo de checksum + **tests unitarios** (validar contra sentencias NMEA de referencia).
3. Módulo **`location/`**: wrapper de `FusedLocationProvider` → modelo `Fix`.
4. Módulo **`net/`**: servidor TCP (y UDP opcional) que difunde cada `Fix` como NMEA a los clientes conectados.
5. **Foreground service** que une location + net; notificación persistente.
6. **UI** (Jetpack Compose): start/stop, IP:puerto, log de estado.
7. **Manejo de red**: detectar IP Wi‑Fi local; aviso si no hay Wi‑Fi; soportar modo hotspot.
8. **Prueba de campo**: conectar Navionics (Paired Devices → IP del teléfono, puerto 2000, TCP) y verificar que la posición aparece y se mueve en la carta.
9. Documentar en `README` el procedimiento de emparejamiento en Navionics.

---

## 5. Criterios de aceptación

- [ ] La tablet, con la app Oshun corriendo en el teléfono y ambos en la misma Wi‑Fi, muestra la posición del teléfono en Navionics.
- [ ] La posición se **actualiza en movimiento** (no queda congelada).
- [ ] Funciona con la **pantalla del teléfono apagada** (foreground service).
- [ ] Sentencias NMEA con **checksum válido** (verificado por tests y por Navionics).
- [ ] La UI muestra **IP y puerto** para facilitar el emparejamiento.

---

## 6. Riesgos y notas

- **Firewall / aislamiento de clientes (AP isolation)** en algunos routers puede bloquear la conexión teléfono↔tablet → usar **hotspot del teléfono** como alternativa.
- **Batería**: emitir GPS a 1 Hz + Wi‑Fi consume; ofrecer intervalo configurable.
- **Precisión NMEA**: respetar formato exacto de lat/lon (`ddmm.mmmm`) y hora UTC, o Navionics ignora la sentencia.
- **iPhone como fuente**: fase 2, con las salvedades de iOS.

---

## 7. Preguntas abiertas (a confirmar antes de codear)

1. **Teléfono fuente**: ¿Android o iPhone? *(MVP asume Android.)*
2. **Tablet**: ¿marca/SO? Solo para documentar bien el emparejamiento.
3. **Red**: ¿router del barco / casa, o hotspot del teléfono?
4. ¿Interesa **UDP broadcast** (varios clientes) además de TCP, o alcanza TCP 1‑a‑1?

---

### Referencias
- [Yacht Devices — Using Navionics Boating App with NMEA Wi‑Fi Gateway](https://www.yachtd.com/news/navonics_app_sonarchart_live.html)
- [Digital Yacht — Using NavLink2 with Navionics Boating App (config Paired Devices)](https://digitalyacht.net/2020/07/08/navlink2-navionics/)
- [NMEA GPS Tether — compartir GPS del móvil por Wi‑Fi](https://gpstether.bricatta.com/)
- [ShareGPS — NMEA TCP/IP](https://www.jillybunch.com/sharegps/nmea-tcp.html)
