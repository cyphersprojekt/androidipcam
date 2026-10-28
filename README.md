# Cámara IP

App Android que convierte el teléfono o la tablet en una cámara IP para reconocimiento facial.

- `GET http://<ip>:8080/photo`: devuelve un JPEG del cuadro actual de la cámara.
  - Opcional: `?width=640` achica la imagen a ese ancho y `?quality=70` cambia la calidad JPEG (1-100).
- `GET http://<ip>:8080/`: health check.
- `rtsp://<ip>:8554/live`: video H.264 para CCTV, VLC o un NVR.
  - Usa RTP por UDP o por TCP: si el NVR tiene la opción, conviene TCP.
  - Sale a 15 cuadros por segundo y 2 Mbps. Para cambiarlo, editá `VIDEO_FPS` y `VIDEO_BITRATE` en `MainActivity.kt`.
  - El video solo se codifica mientras haya algún cliente conectado.
  - Para probarlo desde la PC: `vlc rtsp://<ip>:8554/live` o `ffplay -rtsp_transport tcp rtsp://<ip>:8554/live`.

Las URLs aparecen arriba en la pantalla de la app.

## Modo kiosco

En modo kiosco, la app arranca sola al prender la tablet y no se puede salir de ella. Se bloquean
Inicio, Recientes, la barra de notificaciones y el menú de apagado, y no aparece la pantalla de bloqueo.
Para eso la app tiene que ser **Device Owner** (administradora del dispositivo).

### Preparar cada tablet (una sola vez)

1. **Restablecé la tablet de fábrica.** En el asistente inicial **no agregues ninguna cuenta**, ni de Google
   ni de Samsung. Android no permite asignar un Device Owner si hay cuentas.
   - En Samsung tampoco puede haber Carpeta Segura ni Dual Messenger, porque crean usuarios extra.
2. **Desactivá el bloqueo de pantalla:** Ajustes → Pantalla de bloqueo → Tipo de bloqueo → **Ninguno**.
3. **Activá las Opciones de desarrollador:** tocá 7 veces "Número de compilación" en Ajustes → Acerca de →
   Información de software. Después activá **Depuración USB**.
4. **Instalá la app** desde Android Studio (Run) con la tablet conectada por USB.
5. **Convertí la app en Device Owner:**
   ```bash
   ~/Android/Sdk/platform-tools/adb shell dpm set-device-owner com.example.camaraip/.AdminReceiver
   ```
   Tiene que responder `Success: Device owner set to package ...`.
6. **Abrí la app.** Entra sola en modo kiosco, y la próxima vez que prendas la tablet se va a abrir directamente.

Errores comunes del paso 5:

| Mensaje | Causa |
|---|---|
| `...there are already some accounts on the device` | Hay cuentas de Google o Samsung: quitarlas o restablecer de fábrica. |
| `...there are already several users on the device` | Hay otros usuarios, Carpeta Segura o Dual Messenger. |
| `...the device is already provisioned` | Restablecer de fábrica. |

### Menú de administración

Tocá **5 veces seguidas** (en menos de 3 segundos) el panel de arriba, donde está la IP, e ingresá el PIN.
El PIN por defecto es **`1234`**: cambialo en `ADMIN_PIN`, al final de `MainActivity.kt`.

Desde el menú podés:
- **Cambiar cámara** entre frontal y trasera.
- **Salir del modo kiosco.** Vuelve a activarse desde el mismo menú.
- **Quitar Device Owner.** Es necesario para poder desinstalar la app sin restablecer la tablet.

Si no podés entrar al menú, también se puede quitar el Device Owner por adb. Esto funciona con las versiones
instaladas desde Android Studio:
```bash
~/Android/Sdk/platform-tools/adb shell dpm remove-active-admin com.example.camaraip/.AdminReceiver
```

Las actualizaciones desde Android Studio (Run) funcionan normalmente aunque la app sea Device Owner.
