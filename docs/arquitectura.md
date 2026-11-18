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

El libro de operaciones es una función pura: `Ledger.evaluate` (`core/src/main/kotlin/dev/duardo/neotransfer/core/ledger/Ledger.kt`) recibe lo que se envió, los SMS del banco (`PAGOxMOVIL` y los avisos de `BANDEC`), las tarjetas y la hora, y devuelve el estado de cada operación, lo próximo que hay que enviar, la sesión, los movimientos y los saldos. `AppController` (`app/src/main/kotlin/dev/duardo/neotransfer/engine/AppController.kt`) solo guarda hechos y vuelve a evaluar.

1. **Pedir.** `AppController.submit` comprueba que el banco, o MiTransfer para la Clásica, tenga clave guardada cuando la solicitud la necesita y que no haya un pago idéntico pendiente: mismo origen, destino e importe. Si lo hay, pregunta antes («Ya enviaste… ¿Enviar otro?»); no bloquea. La operación se guarda antes de que nada pueda enviarla.
2. **Turno.** Hay una sola solicitud USSD en curso a la vez: la siguiente sale cuando llega el acuse de la anterior, o a los 45 s sin acuse. La interfaz no se bloquea mientras tanto.
3. **Autenticación encadenada.** Si la operación necesita sesión y no la hay para su banco, el libro crea un `40` automático (identificador `<operación>#auth<n>`, campo `unlocks`) y la operación sale justo tras su acuse, sin esperar el SMS.
4. **Envío.** Se registra el intento (`Operation.sending`) antes de llamar al módem, y después el acuse o el fallo. Cada banco sale por la SIM por la que ha respondido, que la app aprende de los SMS ([protocolo](protocolo.md#12-envío-y-acuse)): en un teléfono con dos SIM, la otra línea no tiene banca móvil con ese banco. Las claves solo están en memoria con la app desbloqueada: un `40` o un pago QR esperan a que se desbloquee. En modo avión o sin servicio el módem no envía nada (`platform/AndroidUssdModem.kt`): la operación queda «No se ejecutó» con «Desactiva el modo avión. No se envió nada.» o «Sin servicio. No se envió nada.», también cuando lo que no salió fue su `40`, y no se reintenta sola. Lo lee sin permisos: `Settings.Global.AIRPLANE_MODE_ON` y el estado de voz que el sistema da a un `TelephonyCallback.ServiceStateListener`.
5. **Resultado.** Los SMS llegan cuando llegan; cada uno vuelve a evaluar el libro. Si la app no está delante, se notifica el resultado. Nunca se reenvía dinero solo.

Estados (`core/src/main/kotlin/dev/duardo/neotransfer/core/ledger/Operation.kt`) y su nombre en pantalla:

| Estado | En pantalla | Cuándo |
|---|---|---|
| `Queued`, `Sending` | — | Esperando turno o saliendo, con su `40` si hace falta |
| `Sent` | Enviada | Acuse «siendo procesada»; se espera el SMS |
| `Retrying` | — | El banco estaba ocupado; una consulta se repite sola una vez a los 15 s |
| `Confirmed` | Confirmada | Llegó el comprobante, saldo o listado que la responde |
| `Rejected` | Rechazada | El banco respondió «Fallo…», «Error…» o «Clave incorrecta», con su motivo |
| `NoResponse` | Sin confirmar | 15 minutos sin SMS. Es neutro: un resultado tardío la sigue cerrando |
| `NotExecuted` | No se ejecutó | No llegó al banco: ocupado, fallo del módem, acuse distinto, autenticación fallida o una línea que el banco no conoce. El dinero ofrece «Reintentar» de un toque, que crea una operación nueva |
| `Answered` | — | Consultas Cubacel y desconexión: el texto USSD es la respuesta |

Una consulta nunca envía una gestión: `49`, `58`, `60`, `68`, `69` y `111` solo salen como su propia petición y el libro nunca los reenvía ([protocolo](protocolo.md#5-servicios-de-la-línea)). La app tampoco consulta por su cuenta; la persona refresca el saldo (`46`) o pide las últimas operaciones (`48`).

## Correlación de SMS

`Ledger.matches` decide qué SMS responde a qué operación:

- Por tipo y banco y, en dinero, por importe y destino: la máscara del beneficiario en transferencias, la cuenta o la factura en servicios, el `Id Compra` en pagos QR. Un ordenante explícito debe coincidir con la tarjeta de origen elegida. Si el comprobante de transferencia admite más de un envío, su referencia registra el movimiento una sola vez y los envíos quedan sin confirmar.
- El SMS debe recibirse después del envío, y haber salido del SMSC como mucho 30 s antes y no más de 2 horas después. Se tolera el desorden: el resultado puede llegar antes que el SMS de autenticación.
- El envío lleva la hora del teléfono y el SMS la marca del SMSC, y un teléfono con la hora puesta a mano puede ir una hora, o unos minutos, por delante o por detrás. Un SMS nunca se recibe antes de salir: la menor diferencia entre recepción y marca de los SMS recibidos en los 30 minutos anteriores, él incluido, es lo más cerca que se puede estar de la diferencia entre ambos relojes, y la marca más esa diferencia es cuándo salió según el teléfono (`Ledger`, `leftAt`). Los SMS que llegan después no la cambian. Con un solo SMS en ese rato no hay con qué distinguir un retraso de un reloj adelantado, y cuenta como recién salido. La sesión y el bloqueo por clave incorrecta usan esa misma hora.
- Una respuesta de consulta cierra las copias pendientes de esa misma consulta. Si BPA devuelve un saldo sin identificar la tarjeta y hay consultas compatibles a distintos orígenes, cada tarjeta conserva su último saldo identificado.
- «Solicitud en proceso» se aplica al último envío anterior a ese SMS.
- Un rechazo que no dice qué rechaza solo se asigna si hay una única candidata.
- Un comprobante y su copia impersonal, o un comprobante repetido, cuentan una vez por referencia.

Los comprobantes sin operación propia (hechos desde Transfermóvil, por ejemplo) entran en la actividad como movimientos externos.

## Actividad

`Activity` (`core/src/main/kotlin/dev/duardo/neotransfer/core/ledger/Activity.kt`) arma los movimientos:

- Uno por referencia bancaria, a partir de comprobantes de transferencia, pago QR, servicio o transferencia recibida. El comprobante enmascara al beneficiario; en una transferencia de la app, el destino completo sale de la operación, y si es una tarjeta propia, el movimiento es entre tarjetas propias y le suma.
- Las filas de «Últimas operaciones» completan lo que ningún comprobante contó: un SMS perdido, compras con la tarjeta, cajeros. Pedidas por la app para una tarjeta concreta, además dicen de quién es cada referencia que listan: ese débito o crédito es de esa tarjeta, aunque su comprobante o una respuesta anterior no nombraran ninguna.
- La atribución a tarjetas se calcula cada vez y no se guarda: añadir una tarjeta después reasigna lo que le corresponde. Un débito con cuenta predeterminada se atribuye si solo una tarjeta de ese banco y moneda puede ser; en BANDEC ayuda la tarjeta que nombra el SMS de autenticación. En BPA deja de atribuirse cuando algún comprobante nombra como ordenante una tarjeta que la cartera no tiene: la línea opera más tarjetas que la cartera, y desde entonces la app envía la suya explícita ([protocolo](protocolo.md#41-origen-y-moneda)).
- Las autenticaciones nunca aparecen como movimientos.

Al instalar, la app importa todos los SMS del banco de la bandeja sin notificar; después lee de forma incremental desde el último `_id`, nunca la bandeja entera por cada SMS.

### Saldo

Saldo de cada tarjeta = último saldo observado + movimientos posteriores atribuidos sin ambigüedad a esa tarjeta (`Activity.project`):

- Saldo observado: un SMS de saldo, o el saldo restante o disponible de un comprobante. En BANDEC cuenta el disponible.
- Un abono se suma al momento si su cuenta destino coincide exactamente con una tarjeta propia.
- `Saldo Restante: DB x` es el saldo `x`, no una deuda.
- Algunos comprobantes de BANDEC traen el saldo anterior al débito: si el saldo del comprobante coincide con el que había y es un débito, se resta el importe.
- Hasta que no se observa un primer saldo, la tarjeta no muestra cifra.

## Sesión bancaria

`Ledger.session` deriva la sesión de las operaciones guardadas: 60 minutos desde el acuse de un `40` aceptado, con un solo banco o MiTransfer activo. Autenticar otro, desconectar o un SMS de autenticación fallida la terminan; una autenticación correcta hecha desde otra app la abre. Como sale de lo guardado, sobrevive a cerrar la app y al bloqueo con huella. Detalle y evidencia en el [protocolo](protocolo.md#2-sesión-bancaria).

## Datos

Room, base `neotransfer.db`, versión 3 (`app/src/main/kotlin/dev/duardo/neotransfer/data/NeoDatabase.kt`; esquemas en `app/schemas/`). De la 2 a la 3, migración automática que solo añade `classic_card` (prueba `NeoDatabaseMigrationTest`):

| Tabla | Contenido |
|---|---|
| `operation` | Cada operación como JSON (`Operation.encode`): la solicitud y sus intentos, sin secretos |
| `sms` | SMS del banco ingeridos: `_id`, hora del SMSC, hora de recepción y texto. Del SMS de registro, que trae la clave y el PIN, no se guarda el texto: el núcleo extrae solo la clave (`SmsMessage.Registration`) |
| `card` | Tarjetas propias en orden de cartera, con titular, vencimiento y alias local opcionales |
| `classic_card` | Clásicas propias, solo el número: no tienen banco y siempre son USD. La cartera las muestra tras las tarjetas de los bancos |
| `contact` | Lo que la app añade a cada persona: tarjetas y un nombre por tarjeta («Salario»). Las de la agenda del teléfono se guardan como `phone:<lookup key>`, con una copia de su nombre y teléfonos para el respaldo; la agenda manda en nombre y teléfonos (`engine/Directory.kt`). Las que solo existen en la app guardan todo |
| `service` | Referencias de servicios guardadas: factura, cuenta Nauta, móvil |
| `setting` | Tema, avisos, cuándo se guardó cada clave (las de los bancos y el PIN de MiTransfer), las claves pendientes de guardar (hora y `_id` del SMS, nunca la clave), cómo se importó la bandeja, las SIM por las que ha respondido cada banco y MiTransfer, y fecha del último respaldo |

`AppController` carga todo al arrancar y mantiene la copia de trabajo en memoria; cada cambio es una escritura. La copia de seguridad de Android está desactivada (`allowBackup="false"` y `data_extraction_rules.xml` excluye todo).

La 1.0 empieza limpia: al primer arranque, `LegacyData` borra lo que dejaron las versiones anteriores con el mismo paquete (hasta 1.0.0-dev.6). No se migra nada; la actividad se reconstruye desde los SMS.

## Seguridad

- **Claves del banco y PIN de MiTransfer** (`platform/BankPinVault.kt`, `engine/BankKeys.kt`): se escriben en el teclado de la app y se cifran con AES-256-GCM bajo una clave del Android Keystore que se autoriza en cada uso con el diálogo del sistema (`platform/BiometricGate.kt`). Un solo blob para los bancos y MiTransfer, descifrado al desbloquear, solo en memoria y borrado al bloquear. La clave del Keystore depende del teléfono (`platform/PhoneKeys.kt`, que las pruebas sustituyen por una falsa):
  - Con huella fuerte registrada: solo la huella, y se invalida al registrar una huella nueva; entonces hay que guardarlas otra vez.
  - Con bloqueo de pantalla seguro y sin huella: la huella o el bloqueo de pantalla (`AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`). El diálogo ofrece los dos y, como exige Android, sin botón «Cancelar»; los botones que lo abren llevan el candado en vez de la huella, como cuando confirma el código de acceso; sin ningún bloqueo no llevan icono (`ui/common/Theme.kt`, `LocalPromptIcon`).
  - Teléfonos cuyo Keystore rechaza la clave de huella justo después de una huella válida (realme Note 60: `UserNotAuthenticatedException` o `KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED` al cifrar): pasan para siempre a la clave de huella o bloqueo de pantalla. El primer guardado se repite en el acto con otro diálogo (`VaultMoved`).
  - Sin bloqueo de pantalla seguro y sin el código de acceso protegiéndolas: no se guardan («Pon un bloqueo de pantalla en el teléfono para guardar la clave»). Cada envío pide la clave, que vive en memoria hasta que sale (`AppController.useKey`).

  La clave que guarda el blob lo sigue guardando hasta vaciarlo. Donde este documento dice «la huella», en un teléfono sin huella es el bloqueo de pantalla.
- **Clave que trae el registro** (`49`): el SMS de respuesta lleva la clave y el PIN de la tarjeta en claro. La app no guarda su texto ni la clave: anota la hora y el `_id` del SMS, y al guardarla con la huella (`AppController.saveReceivedKey`) la vuelve a leer de la bandeja, la cifra como una clave escrita y la borra de memoria. Si el SMS llega con la app bloqueada o el proceso muere antes, la clave sigue solo en la bandeja del teléfono hasta que, tras desbloquear, la huella la guarde; si el SMS ya no está, se escribe a mano. Mientras espera, nada que necesite la clave de ese banco sale.
- **Clave nueva** (`69`, y el PIN que se elige al registrar la línea en MiTransfer): se escribe dos veces y vive en memoria, con la app desbloqueada, hasta la respuesta. Confirmada, espera la huella para sustituir a la guardada; rechazada o sin ejecutar, se borra y la guardada sigue igual. Si la app se bloquea antes, hay que escribirla de nuevo, o descartarla.
- **Respuesta del `69`**: el lector no conoce el SMS de éxito ([protocolo](protocolo.md#5-servicios-de-la-línea)). Un SMS de `PAGOxMOVIL` de redacción desconocida que llega con un `69` pendiente lo responde: la app enseña su texto y la clave nueva espera; la persona lo lee y la guarda con la huella (`saveReceivedKey`) o la descarta (`discardReceivedKey`), y entonces vuelve a usarse la guardada. Mientras espera, nada sale con la clave vieja. Si el texto nombra un banco, responde al `69` de ese banco; si no nombra ninguno, solo cuando hay un único `69` pendiente: con dos de bancos distintos no responde a ninguno y cada uno acaba «Sin respuesta».
- **Clave en el texto**: ese SMS puede traer la clave nueva en claro. Mientras haya un `69` pendiente, antes de guardar o evaluar un SMS de redacción desconocida, la app tapa con `•` cada tirada de dígitos de la longitud de la clave de ese banco (`AppController.maskKeys`), sin suponer plantilla: la tabla `sms` y el texto que se muestra nunca la llevan, y fechas o importes de esa longitud también salen tapados. Una clave escrita con separadores entre sus dígitos no se reconocería.
- **Almacén sin descifrar**: si la app se desbloquea sin que la huella descifre el almacén (biometría no disponible, un descifrado que falla, o se abrió con el código de acceso), las claves no están en memoria y la app no guarda nada encima, porque el blob es uno solo y se perderían las demás: `AppController.keysLocked` pide volver a desbloquear con la huella, y hasta entonces nada que necesite clave sale. Un envío que queda esperándolas (`AppController.keysWanted`) las pide en el acto.
- **Bloqueo**: huella (o bloqueo de pantalla) al abrir y al volver tras 1 minuto en segundo plano (`AppController.LOCK_AFTER`); sin ningún bloqueo, la app abre directamente. Con la biometría bloqueada por intentos se espera; si no está disponible por otra causa y hay bloqueo de pantalla, lo pide el bloqueo de pantalla sin abrir las claves (la app abre con `keysLocked`, un envío se confirma así). Cada guardado usa la clave del Keystore con la que se creó su cifrado, de principio a fin (`KeyVault.lockOf`). Bloquear la interfaz no cierra la sesión bancaria.
- **Código de acceso** (opcional, Ajustes; `platform/AndroidAppCode.kt`, `app/CodeFlow.kt`): 6 dígitos. Con él activo la app abre con el código o con la huella, nunca con el PIN o patrón del teléfono. Nunca se guarda: su clave es PBKDF2-HMAC-SHA256 con sal aleatoria y las vueltas que tardan unos 250 ms en ese teléfono (medidas al crearlo, entre 100.000 y 2.000.000), y se reconoce descifrando un blob conocido (etiqueta GCM) envuelto por una clave del Keystore sin autenticación, que lo ata al teléfono.
  - La huella lo sustituye con una clave propia, de huella y por uso, creada al poner el código: una huella registrada después nunca abre la app. Invalidada, la huella no se ofrece hasta escribir el código, que la vuelve a crear; en teléfonos como el realme solo se ofrece el código.
  - Desde el 5.º fallo seguido, espera de 30 s que se duplica en cada fallo, hasta 1 hora (`AppController.enterCode`). Contador y espera sobreviven a reiniciar la app; la espera se mide con el reloj desde el arranque (`elapsedRealtime` y `Settings.Global.BOOT_COUNT`), así que cambiar la hora no la acorta ni la alarga, y tras reiniciar el teléfono vuelve a empezar entera.
  - «Usar también para las claves»: el blob se cifra bajo la clave del código y se envuelve con la clave del Keystore sin autenticación; entonces el código sustituye a la huella donde se abre una clave (desbloquear y confirmar un envío). Activarlo o desactivarlo vuelve a cifrar lo guardado con un diálogo del método actual; no se pierde nada. Con esa opción, los fallos 8.º y 9.º avisan y el 10.º (o cualquiera posterior, si el proceso murió antes de borrar) borra las claves guardadas (solo las claves). Al cambiar el código, las claves pasan al nuevo antes de fijarlo; si un código correcto ya no las abre, se dan por perdidas, se borran y la app abre. Si están bajo el código lo dice dónde están guardadas, no el ajuste.
  - «¿Olvidaste el código?»: con la huella válida, se crea un código nuevo (las claves que guardaba el olvidado se pierden); si no, la hoja abre directamente en la confirmación de borrar los datos de la app (`ActivityManager.clearApplicationUserData`).
- **Dinero**: la huella confirma lo que se revisó; con las claves bajo el código de acceso, el código. Una clave rechazada por el banco bloquea los envíos automáticos de ese banco hasta guardarla de nuevo.
- **Clave de transferencia Cubacel y valor de una coordenada** (el `60` de Metropolitano): se escriben en cada envío y solo viven en memoria hasta que sale.
- **Capturas**: permitidas, salvo en las pantallas con secretos (clave del banco, código de acceso, respaldo y clave de transferencia), que usan `FLAG_SECURE`.
- **Notificaciones**: privadas, con una versión pública genérica para la pantalla de bloqueo («Transferencia recibida», «Operación confirmada»).
- **Permisos**: `CALL_PHONE`, `READ_SMS`, `RECEIVE_SMS`, `USE_BIOMETRIC`, `CAMERA`, `READ_CONTACTS` y `POST_NOTIFICATIONS`. Sin `INTERNET`: el manifiesto retira el que declara ML Kit para sus informes de uso (`app/src/main/AndroidManifest.xml`), así que nada sale del teléfono.
- **Sincronización con NeoTransfer+** (`platform/PeerSync.kt`): cada app lee los contactos y la actividad de la otra por su `ContentProvider`, protegido por el permiso de firma `dev.duardo.neotransfer.permission.SYNC_CONTACTS`. En `debug` y `preview` el permiso y la autoridad de la otra app llevan el sufijo de la variante (`.debug`, `.preview`): cada variante sincroniza con la misma de la otra app y se instala junto a la publicada.

## Escáner

`scanner/src/main/kotlin/dev/duardo/neotransfer/scanner/`:

- `ScannerView`: cámara a pantalla completa con CameraX, linterna, zoom con botones y pellizco, enfoque al tocar y galería. El recuadro cae sobre el QR detectado.
- Lectura: ML Kit empaquetado en la app, sin depender de Google Play Services, y ZXing para los QR invertidos (`CameraAnalyzer.kt`, `ZxingQr.kt`). En imágenes prueba la imagen entera, su negativo y después por mosaicos, para un QR pequeño en una foto grande (`ImageScan.kt`).
- Números (`NumberReader.kt`): tarjetas cubanas (16 dígitos, empiezan por 92, dígito de control Luhn) y móviles (8 dígitos que empiezan por 5 o 6, con `53` opcional), impresos o escritos a mano. Une grupos partidos entre palabras o líneas. Un número se propone tras leerlo igual en varios cuadros (`Tracking.kt`) y la persona lo confirma o corrige.
- Accesos: dentro de la app (Pagar, destinatario, alta de tarjeta, contactos y campos de móvil de servicios), el atajo «Escanear» al mantener pulsado el icono (`app/src/main/shortcuts.xml`), el mosaico de Ajustes rápidos (`platform/ScanTileService.kt`) y los enlaces de pago de Transfermóvil.

## Respaldo

`core/src/main/kotlin/dev/duardo/neotransfer/core/backup/`:

- **Respaldo propio** (`.ntbackup`, formato 4): tarjetas con titular, vencimiento y alias, Clásicas, contactos y referencias de servicios. Nunca lleva claves del banco, el PIN de MiTransfer, claves de transferencia ni la actividad, que se reconstruye desde los SMS. Se cifra con contraseña obligatoria (`BackupEnvelope.kt`): AES-256-GCM con la cabecera como datos asociados y clave derivada con PBKDF2-HMAC-SHA256 de 600.000 iteraciones. Importar añade lo que falta y no sobrescribe.
- **Respaldo de Transfermóvil** (`.trm`, `TrmImporter.kt`): importa tarjetas BPA, BANDEC y Metropolitano, contactos con sus teléfonos y tarjetas, cuentas Nauta y facturas de electricidad y teléfono. Nunca lee la tabla de claves (`AOperKey`), los códigos de recarga ni los comprobantes.
