# Arquitectura

NeoTransfer manda cada operación por USSD y aplica su resultado cuando llega el SMS del banco, sin servidor propio. Esta página resume cómo está hecho; el formato de cada mensaje está en el [protocolo](protocolo.md).

## Módulos

| Módulo | Qué contiene | Depende de |
|---|---|---|
| `core` | Kotlin puro (JVM), sin Android: protocolo, libro de operaciones, lectura de SMS, QR, respaldo y modelo | — |
| `designsystem` | Tema (menta `#3DDC97` en oscuro y `#1FB36F` en claro, sobre grafito), tipografías Inter y Arimo, iconos, componentes Compose y la cara de la tarjeta | — |
| `scanner` | Cámara y lectura de imágenes: QR y números de tarjeta y móvil | — |
| `app` | La app Android: motor, datos, plataforma y pantallas | los tres anteriores |

`scanner` no conoce ningún formato de pago: devuelve el texto del QR y la app decide si sirve.

Paquetes de `app/src/main/kotlin/dev/duardo/neotransfer/`:

- `engine/`: `AppController`, que une el núcleo con la interfaz, y los puertos hacia el teléfono (`Ports.kt`: módem, bandeja, almacén de claves, notificaciones, documentos y contactos).
- `data/`: Room y la correspondencia entre filas y modelo.
- `platform/`: implementaciones Android de los puertos, biometría y el mosaico de Ajustes rápidos.
- `app/`: navegación y flujos (`TransferFlow`, `PayFlow`, `ServiceFlow`, `SettingsFlow`, `Setup`).
- `ui/`: pantallas sin estado, que reciben un modelo y unas acciones.

La variante `preview` añade `app/src/preview/`: un banco simulado y un catálogo de pantallas con datos inventados ([desarrollo](desarrollo.md#variantes)).

## Enviar y seguir

El libro de operaciones es una función pura: `Ledger.evaluate` (`core/src/main/kotlin/dev/duardo/neotransfer/core/ledger/Ledger.kt`) recibe lo que se envió, los SMS de `PAGOxMOVIL`, las tarjetas y la hora, y devuelve el estado de cada operación, lo próximo que hay que enviar, la sesión, los movimientos y los saldos. `AppController` (`app/src/main/kotlin/dev/duardo/neotransfer/engine/AppController.kt`) solo guarda hechos y vuelve a evaluar.

1. **Pedir.** `AppController.submit` comprueba que el banco tenga clave guardada y que no haya un pago idéntico pendiente: mismo origen, destino e importe. Si lo hay, pregunta antes («Ya enviaste… ¿Enviar otro?»); no bloquea. La operación se guarda antes de que nada pueda enviarla.
2. **Turno.** Hay una sola solicitud USSD en curso a la vez: la siguiente sale cuando llega el acuse de la anterior, o a los 45 s sin acuse. La interfaz no se bloquea mientras tanto.
3. **Autenticación encadenada.** Si la operación necesita sesión y no la hay para su banco, el libro crea un `40` automático (identificador `<operación>#auth<n>`, campo `unlocks`) y la operación sale justo tras su acuse, sin esperar el SMS.
4. **Envío.** Se registra el intento (`Operation.sending`) antes de llamar al módem, y después el acuse o el fallo. Las claves solo están en memoria con la app desbloqueada: un `40` o un pago QR esperan a que se desbloquee.
5. **Resultado.** Los SMS llegan cuando llegan; cada uno vuelve a evaluar el libro. Si la app no está delante, se notifica el resultado. Nunca se reenvía dinero solo.

Estados (`core/src/main/kotlin/dev/duardo/neotransfer/core/ledger/Operation.kt`) y su nombre en pantalla:

| Estado | En pantalla | Cuándo |
|---|---|---|
| `Queued`, `Sending` | — | Esperando turno o saliendo, con su `40` si hace falta |
| `Sent` | Enviada | Acuse «siendo procesada»; se espera el SMS |
| `Retrying` | — | El banco estaba ocupado; una consulta se repite sola una vez a los 15 s |
| `Confirmed` | Confirmada | Llegó el comprobante, saldo o listado que la responde |
| `Rejected` | Rechazada | El banco respondió «Fallo…», «Error…» o «Clave incorrecta», con su motivo |
| `NoResponse` | Sin respuesta | 15 minutos sin SMS. Es neutro: un resultado tardío la sigue cerrando |
| `NotExecuted` | No se ejecutó | No llegó al banco: ocupado, fallo del módem, acuse distinto o autenticación fallida. El dinero ofrece «Reintentar» de un toque, que crea una operación nueva |
| `Answered` | — | Consultas Cubacel y desconexión: el texto USSD es la respuesta |

Una consulta nunca envía una gestión: ninguna operación produce `60`, `49`, `58`, `68` ni `69`. La app tampoco consulta por su cuenta; la persona refresca el saldo (`46`) o pide las últimas operaciones (`48`).

## Correlación de SMS

`Ledger.matches` decide qué SMS responde a qué operación:

- Por tipo y banco y, en dinero, por importe y destino: la máscara del beneficiario en transferencias, la cuenta o la factura en servicios, el `Id Compra` en pagos QR.
- El SMS debe recibirse después del envío, con una marca del SMSC hasta 30 s anterior y no más de 2 horas posterior. Se tolera el desorden: el resultado puede llegar antes que el SMS de autenticación.
- Una respuesta de consulta cierra todas las copias pendientes de esa misma consulta.
- «Solicitud en proceso» se aplica al último envío anterior a ese SMS.
- Un rechazo que no dice qué rechaza solo se asigna si hay una única candidata.
- Un comprobante y su copia impersonal, o un comprobante repetido, cuentan una vez por referencia.

Los comprobantes sin operación propia (hechos desde Transfermóvil, por ejemplo) entran en la actividad como movimientos externos.

## Actividad

`Activity` (`core/src/main/kotlin/dev/duardo/neotransfer/core/ledger/Activity.kt`) arma los movimientos:

- Uno por referencia bancaria, a partir de comprobantes de transferencia, pago QR, servicio o transferencia recibida.
- Las filas de «Últimas operaciones» completan lo que ningún comprobante contó: un SMS perdido, compras con la tarjeta, cajeros.
- La atribución a tarjetas se calcula cada vez y no se guarda: añadir una tarjeta después reasigna lo que le corresponde. Un débito con cuenta predeterminada se atribuye si solo una tarjeta de ese banco y moneda puede ser; en BANDEC ayuda la tarjeta que nombra el SMS de autenticación.
- Las autenticaciones nunca aparecen como movimientos.

Al instalar, la app importa todos los SMS de `PAGOxMOVIL` de la bandeja sin notificar; después lee de forma incremental desde el último `_id`, nunca la bandeja entera por cada SMS.

### Saldo

Saldo de cada tarjeta = último saldo observado + movimientos posteriores atribuidos sin ambigüedad a esa tarjeta (`Activity.project`):

- Saldo observado: un SMS de saldo, o el saldo restante o disponible de un comprobante. En BANDEC cuenta el disponible.
- Un abono se suma al momento si su cuenta destino coincide exactamente con una tarjeta propia.
- `Saldo Restante: DB x` es el saldo `x`, no una deuda.
- Algunos comprobantes de BANDEC traen el saldo anterior al débito: si el saldo del comprobante coincide con el que había y es un débito, se resta el importe.
- Hasta que no se observa un primer saldo, la tarjeta no muestra cifra.

## Sesión bancaria

`Ledger.session` deriva la sesión de las operaciones guardadas: 60 minutos desde el acuse de un `40` aceptado, con un solo banco activo. Autenticar el otro banco, desconectar o un SMS de autenticación fallida la terminan; una autenticación correcta hecha desde otra app la abre. Como sale de lo guardado, sobrevive a cerrar la app y al bloqueo con huella. Detalle y evidencia en el [protocolo](protocolo.md#2-sesión-bancaria).

## Datos

Room, base `neotransfer.db`, versión 2 (`app/src/main/kotlin/dev/duardo/neotransfer/data/NeoDatabase.kt`; esquemas en `app/schemas/`):

| Tabla | Contenido |
|---|---|
| `operation` | Cada operación como JSON (`Operation.encode`): la solicitud y sus intentos, sin secretos |
| `sms` | SMS de `PAGOxMOVIL` ingeridos: `_id`, hora del SMSC, hora de recepción y texto. Del SMS de registro, que trae la clave y el PIN, no se guarda el texto |
| `card` | Tarjetas propias en orden de cartera, con titular y vencimiento opcionales |
| `contact` | Contactos con varios teléfonos y tarjetas, y un nombre por tarjeta («Salario») |
| `service` | Referencias de servicios guardadas: factura, cuenta Nauta, móvil |
| `setting` | Tema, avisos, cuándo se guardó cada clave, si ya se importó la bandeja y fecha del último respaldo |

`AppController` carga todo al arrancar y mantiene la copia de trabajo en memoria; cada cambio es una escritura. La copia de seguridad de Android está desactivada (`allowBackup="false"` y `data_extraction_rules.xml` excluye todo).

La 1.0 empieza limpia: al primer arranque, `LegacyData` borra lo que dejaron las versiones anteriores con el mismo paquete (hasta 1.0.0-dev.6). No se migra nada; la actividad se reconstruye desde los SMS.

## Seguridad

- **Claves del banco** (`platform/BankPinVault.kt`, `engine/BankKeys.kt`): se escriben en el teclado de la app y se cifran con AES-256-GCM bajo una clave del Android Keystore que exige biometría fuerte en cada uso y se invalida al registrar una huella nueva; entonces hay que guardarlas otra vez. Un solo blob para los dos bancos, descifrado al desbloquear, solo en memoria y borrado al bloquear.
- **Bloqueo**: huella al abrir y al volver tras 1 minuto en segundo plano (`AppController.LOCK_AFTER`). Bloquear la interfaz no cierra la sesión bancaria.
- **Dinero**: la huella confirma lo que se revisó. Una clave rechazada por el banco bloquea los envíos automáticos de ese banco hasta guardarla de nuevo.
- **Clave de transferencia Cubacel**: se escribe en cada envío y solo vive en memoria hasta que sale.
- **Capturas**: permitidas, salvo en las pantallas con secretos (clave del banco, respaldo y clave de transferencia), que usan `FLAG_SECURE`.
- **Notificaciones**: privadas, con una versión pública genérica para la pantalla de bloqueo («Transferencia recibida», «Operación confirmada»).
- **Permisos**: `CALL_PHONE`, `READ_SMS`, `RECEIVE_SMS`, `USE_BIOMETRIC`, `CAMERA`, `READ_CONTACTS` y `POST_NOTIFICATIONS`. El código de NeoTransfer no hace llamadas de red. ML Kit, la biblioteca del escáner, declara `INTERNET` y puede enviar a Google datos técnicos de su uso ([divulgación de Google](https://developers.google.com/ml-kit/android-data-disclosure)).

## Escáner

`scanner/src/main/kotlin/dev/duardo/neotransfer/scanner/`:

- `ScannerView`: cámara a pantalla completa con CameraX, linterna, zoom con botones y pellizco, enfoque al tocar y galería. El recuadro cae sobre el QR detectado.
- Lectura: ML Kit empaquetado en la app, sin depender de Google Play Services, y ZXing para los QR invertidos (`CameraAnalyzer.kt`, `ZxingQr.kt`). En imágenes prueba la imagen entera, su negativo y después por mosaicos, para un QR pequeño en una foto grande (`ImageScan.kt`).
- Números (`NumberReader.kt`): tarjetas cubanas (16 dígitos, empiezan por 92, dígito de control Luhn) y móviles (8 dígitos que empiezan por 5 o 6, con `53` opcional), impresos o escritos a mano. Une grupos partidos entre palabras o líneas. Un número se propone tras leerlo igual en varios cuadros (`Tracking.kt`) y la persona lo confirma o corrige.
- Accesos: dentro de la app (Pagar, destinatario, alta de tarjeta, contactos y campos de móvil de servicios), el atajo «Escanear» al mantener pulsado el icono (`app/src/main/shortcuts.xml`), el mosaico de Ajustes rápidos (`platform/ScanTileService.kt`) y los enlaces de pago de Transfermóvil.

## Respaldo

`core/src/main/kotlin/dev/duardo/neotransfer/core/backup/`:

- **Respaldo propio** (`.ntbackup`, formato 4): tarjetas con titular y vencimiento, contactos y referencias de servicios. Nunca lleva claves del banco, claves de transferencia ni la actividad, que se reconstruye desde los SMS. Se cifra con contraseña obligatoria (`BackupEnvelope.kt`): AES-256-GCM con la cabecera como datos asociados y clave derivada con PBKDF2-HMAC-SHA256 de 600.000 iteraciones. Importar añade lo que falta y no sobrescribe.
- **Respaldo de Transfermóvil** (`.trm`, `TrmImporter.kt`): importa tarjetas BPA y BANDEC, contactos con sus teléfonos y tarjetas, cuentas Nauta y facturas de electricidad y teléfono. Nunca lee la tabla de claves (`AOperKey`), los códigos de recarga ni los comprobantes.
