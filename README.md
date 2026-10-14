# AutoToques

App Android para **grabar tus toques en la pantalla** y **reproducirlos programados** (días y hora), despertando el celular.

## Cómo conseguir el APK

**Opción A — GitHub (sin instalar nada en la PC)**
1. Subí esta carpeta a un repositorio de GitHub (puede ser privado).
2. GitHub compila solo (pestaña *Actions*, tarda ~3 min).
3. Desde el celular entrá a *Releases → latest* y bajá `AutoToques.apk`.

**Opción B — Android Studio**
Abrí la carpeta en Android Studio → *Build → Build APK(s)*.

## Instalación en el celular

1. Instalá el APK (aceptá "instalar apps de origen desconocido").
2. Abrí AutoToques. Arriba vas a ver la lista de configuración:
   - **Servicio de accesibilidad** → *Activar* → buscá "AutoToques" y activalo.
     - Si aparece **gris / "ajuste restringido"** (Android 13+): Ajustes → Apps → AutoToques → menú **⋮** → **Permitir ajustes restringidos**. Después volvé a Accesibilidad.
   - **Sin restricción de batería** → *Quitar*.
   - **Pantalla de bloqueo**: tiene que estar en "Ninguno" o "Deslizar". Con PIN/patrón/huella Android no deja que ninguna app desbloquee.
   - Aceptá las notificaciones (sirven para el botón **Detener**).

### Xiaomi / Redmi / POCO (MIUI / HyperOS)
- Ajustes → Apps → AutoToques → **Inicio automático**: activado.
- Ahorro de batería de la app: **Sin restricciones**.
- En Recientes, bloqueá la app (candado) para que no la cierre.

### Samsung
- Ajustes → Batería → Límites de uso en segundo plano → sacá AutoToques de "apps en suspensión" y agregala a "**apps que nunca se suspenden**".

### Motorola / otros
- Ajustes → Apps → AutoToques → Batería → **Sin restricciones**.

## Uso

1. **● Nueva grabación**: la app te lleva a la pantalla de inicio y aparece un tinte rojo suave + una barrita flotante.
2. Hacé lo que querés automatizar con normalidad (toques, deslizar, mantener presionado, escribir con el teclado).
   - Usá la barrita para **◁ Atrás**, **○ Inicio** y **▢ Recientes** (no uses los gestos/botones del sistema mientras grabás).
   - **⠿** sirve para arrastrar la barrita si tapa algo.
   - Hacé los toques de a uno y con calma: cada toque se "reenvía" a la app de abajo apenas levantás el dedo.
3. **Guardar** → ponele nombre → **Programar**: hora, días (o ninguno = una sola vez), espera inicial y pausa extra.
4. **▶ Probar** para verificar antes de dejarlo programado.

A la hora programada: se enciende la pantalla, se quita el bloqueo (si no tiene código), va a la pantalla de inicio y reproduce todo con los mismos tiempos que grabaste. Para cortarla: **Detener** en la notificación.

## Consejos
- Grabá siempre empezando desde la pantalla de inicio y abrí la app tocando su ícono (la reproducción también arranca desde inicio). No muevas los íconos de lugar después.
- Si a la madrugada las apps cargan más lento, subí la **pausa extra entre pasos** (p. ej. 500–1500 ms).
- Los toques se reproducen por **posición en pantalla**: si una app cambia su diseño o aparece un cartel inesperado, hay que volver a grabar.
- Rotación: grabá y reproducí con la misma orientación.
- Un solo dedo (sin pellizcar/zoom de dos dedos).

## Técnico
- Java puro, sin dependencias externas. minSdk 27 (Android 8.1), targetSdk 34.
- `MacroService` (AccessibilityService): capa de grabación + `dispatchGesture` para reproducir.
- `Scheduler`: `AlarmManager.setAlarmClock` (el tipo de alarma más confiable; muestra el ícono de alarma en la barra).
- `Waker` + `WakeActivity`: encienden la pantalla y piden quitar el bloqueo.
- Grabaciones en `files/macros.json` (privado de la app).
- La firma (`app/autotoques.keystore`) es fija para que las actualizaciones se instalen encima sin perder grabaciones. Es una clave de uso personal: mantené el repositorio privado.
