# Protocolo de pagos móviles por USSD y SMS

Referencia del protocolo que NeoTransfer usa con BPA (agencia `01`) y BANDEC (`02`), y de los códigos Cubacel que ofrece. No es una especificación oficial: describe lo que hace el cliente Transfermóvil, lo que se observa en los SMS del banco y lo que envía NeoTransfer. Revisado el 2026-10-15.

## Procedencia

Cada apartado indica de dónde sale cada dato:

- **[TM]**: Transfermóvil 1.260416 (versionCode 162, paquete `cu.etecsa.cubacel.tr.tm`), descompilado con JADX 1.5.6 sin desofuscar y contrastado con el bytecode donde JADX se equivoca. La sesión y el servicio 60 se cotejaron también con la 1.250620. Las citas `Clase:línea` usan los nombres ofuscados ([apéndice A](#apéndice-a-clases-de-transfermóvil-citadas)). El código de TM describe al cliente, no lo que acepta el servidor.
- **[SMS]**: la bandeja de un teléfono con uso real de ambos bancos, leída sin modificarla el 2026-10-15: 251 SMS de `PAGOxMOVIL` entre el 2025-06-10 y el 2026-10-13 (123 de BANDEC, 97 de BPA y 31 sin banco en el texto). Aquí solo aparecen cifras agregadas y plantillas con marcadores; ningún dato real va al repositorio ni a las pruebas.
- **[Pública]**: [rodnye/ussd-codes-cuba](https://github.com/rodnye/ussd-codes-cuba) · [Códigos USSD Transfermóvil, Portal de Holguín](https://www.holguin.gob.cu/es/codigos-ussd-transfermovil) · [Códigos BANDEC, Directorio Cubano](https://www.directoriocubano.info/acontecer/asi-puedes-usar-transfermovil-sin-la-app-todos-los-codigos-de-bandec-paso-a-paso/) · [Registro, sesión y SMS, Cubatramite](https://cubatramite.com/pagar-en-linea-desde-la-aplicacion-transfermovil/) · [Generador de QR de comercio, roberticovinales/transfermovil](https://github.com/roberticovinales/transfermovil/blob/main/crearpago.php).
- **NeoTransfer**: ruta del código que lo implementa y prueba que lo fija. Las rutas de `core` cuelgan de `core/src/main/kotlin/dev/duardo/neotransfer/core/`.
- **[no verificado]**: sin evidencia del servidor. Se marca una vez, donde aparece el hecho.

## 1. Transporte

### 1.1 Formato

- Operación bancaria: `*444*<servicio>*<parámetros codificados>*1260416#`. `444` es el código de acceso y `1260416` la versión del cliente; TM los toma de su `strings.xml` al crear `AppConfig` (`S9ycHcoxAzy:754-758`, `IHVTCBmiLV:865-870`). La versión que trae el SMS de bienvenida solo sirve para validar códigos de activación (`g0xM9kQCsqu:1625-1649`); no actualiza nada.
- Al primer arranque y tras actualizarse, TM envía `*444*100*<versión>#` (`S9ycHcoxAzy:754-776`, `IHVTCBmiLV:862-890`). NeoTransfer no lo envía.
- Consultas sin parámetros: `*444*<servicio>#`, sin codificar y sin versión (`rDEl7rW1aN:676-691`). TM las usa como consultas directas, no como menús: 46 de BANDEC, 48 desde algunos accesos (`KmgeIVr5RG:297-304`; sus formularios llevan parámetros, §4.2), 58, 62 de BANDEC, 70, 72, 73, 78, 85, 103 y 117.
- Monedas en el cable: `1` CUP, `2` CUC, `3` USD. CUC salió de circulación ([Decreto-Ley 17/2020, art. 2](https://www.gacetaoficial.gob.cu/sites/default/files/goc-2020-ex68.pdf#page=4)); su código sigue reservado, pero NeoTransfer no opera en CUC y rechaza un QR en esa moneda.

NeoTransfer: `protocol/Requests.kt`, `protocol/UssdCommand.kt`.

### 1.2 Envío y acuse

- **[TM]** Todo el modo normal sale por `Intent.ACTION_CALL` con `tel:*444*…%23` (el `#` codificado), tras un diálogo «Confirmar» (`FBsQzP3Zfj:144-183`). El marcador del sistema muestra la respuesta USSD; TM no la lee y cierra el formulario (`FBsQzP3Zfj:166-171`).
- **[TM]** `TelephonyManager.sendUssdRequest` solo se usa en el modo parcial (§1.3).
- **NeoTransfer** envía las operaciones bancarias y las consultas Cubacel con `sendUssdRequest` por la SIM predeterminada para llamadas, y lee el acuse (`app/src/main/kotlin/dev/duardo/neotransfer/platform/AndroidUssdModem.kt`). Solo el menú de paquetes `*133#` sale por `ACTION_CALL`.
- Acuses que distingue NeoTransfer (`protocol/BankResponse.kt`; compara sin tildes ni mayúsculas):
  - `Su solicitud esta siendo procesada…`: la plataforma aceptó la solicitud. No confirma la operación ni dice qué banco atiende: el resultado llega por SMS.
  - `Usted ya se encuentra autenticado en el sistema`: respuesta a un `40` con sesión abierta. Solo llega por USSD; no deja SMS **[SMS]**.
  - Cualquier otro texto: la red no aceptó la solicitud y NeoTransfer la da por no ejecutada.

### 1.3 Modo parcial (servicio 110)

**[TM]** Con `AppConfig.activarussdparcial` (`Agg55uYctq:173`), una cadena larga se parte en paquetes:

`*444*110*<pp><nn>*<idsecuencia>*<servicio>*<agencia><parte>*<versión>#`

- `pp` es el número de paquete y `nn` el total, con dos dígitos cada uno. `idsecuencia` son los minutos y segundos actuales (`m` o `mm` sin cero inicial, más `ss`). `<parte>` empieza por `*`.
- Se codifica la cadena entera una vez y se agrupan sus átomos separados por `*`; solo el primero lleva la semilla. Un átomo abre paquete nuevo cuando `átomo.length + grupo.length + 32 > 52`; la comparación no cuenta el separador nuevo y un átomo nunca se divide. Ni `MAX_PARAMETERS_PQUETE=4` ni `MAX_FALLAS=3` intervienen.
- En Android 8 o posterior, cada paquete sale con `sendUssdRequest` y el siguiente solo si la respuesta contiene `siendo procesada,`; si no, «Error Enviado - parte N» y no reintenta (`MX9gh9mLxd:52-104`, `pAzq93vIl3:94-128`). En versiones anteriores manda un `ACTION_CALL` por paquete y un servicio de accesibilidad pulsa «OK» para disparar el siguiente (`pAzq93vIl3:228-276`, `O7xyZJJBFWg:38-97`).
- En modo parcial la transferencia **reordena** sus campos: `<destino>*<importe>*<monImporte>*<origen>*<enviarMiMóvil>*<monTarjeta>*<móvil>*<mensaje>*<enviarSaldo>` (`iN1lHDKE5h:648`, `ZDkigMAIBm:569`). Que el servidor acepte ese orden: **[no verificado]**.

**NeoTransfer** solo usa el modo parcial para un pago QR cuya llamada pase de 182 caracteres, el máximo de una solicitud USSD en GSM de 7 bits; nunca para la transferencia (`protocol/UssdCommand.kt`, `QrPay.commands`). Pruebas: `core/src/test/resources/partial-oracle.tsv` y el empaquetado de los 800 vectores de `qr-vectors.tsv` (`ProtocolContractTest`).

## 2. Sesión bancaria

- Autenticar: servicio `40` con `<agencia>*<clave>`: BPA `01*<clave de 4 dígitos>`, BANDEC `02*<clave de 5 dígitos>` (`lzGEfceoJu:45,56`; `TGFziPh3KJ:45,56`). Desconectar: `*444*70#` (`R7PmVxXLJZC:329-343`).
- **[TM]**
  - Marca la sesión al pulsar «Confirmar» del `40`, antes del `ACTION_CALL`: guarda banco y hora sin esperar acuse ni SMS (`FBsQzP3Zfj:563-587`).
  - La da por válida 1 hora (`FBsQzP3Zfj:625-636`, `Calendar.add(HOUR,1)`; igual en 1.250620).
  - Solo la usa para marcar el banco «actual» en el menú lateral (`menuControlBanks`, `FBsQzP3Zfj:674-733`). No bloquea operaciones, no reautentica y no encadena nada: tras el `40` vuelve atrás y la persona lanza la operación cuando quiere.
  - Hay un solo banco activo: autenticar el otro lo sobrescribe sin enviar `70` por el anterior (`FBsQzP3Zfj:571-577`).
- Llevan la clave siempre: el pago QR o en línea `30`/`31` (`r7Rinp9ooL:220-230`) y Caja Extra `32` (`UTk9UWZYxf:347-368`). La transferencia `45` tiene una variante con clave que solo activa `f6Y2Wmtg6jQ.enviarDatos:1054-1080`, una clase que nadie instancia: está inactiva.
- **[SMS]**
  - El servidor aceptó operaciones 72, 87,5 y 90,5 minutos después de la última autenticación. Es una cota inferior; la caducidad real de la sesión: **[no verificado]**. Ningún SMS anuncia una sesión caducada.
  - En 21 de 64 pares autenticación→operación del mismo banco (33 %), el resultado de la operación salió del SMSC antes de que llegara el SMS de la autenticación: la operación se envía tras el acuse del `40`, sin esperar su SMS.
  - Hubo 9 cambios BPA↔BANDEC, siempre con autenticación nueva, y nunca una operación del banco anterior después del cambio. Si autenticar un banco cierra la sesión del otro en el servidor: **[no verificado]**.
  - De 5 pagos QR, 3 se aceptaron sin autenticación del mismo banco en las 11 a 63 horas anteriores, coherente con que `30`/`31` lleven la clave.
- **NeoTransfer** (`ledger/Ledger.kt`, `Ledger.session`):
  - Si una operación necesita sesión y no la hay para su banco, envía un `40` con la clave guardada y la operación sale justo tras el acuse de ese `40`.
  - La sesión dura 60 minutos desde ese acuse (o desde `Usted ya se encuentra autenticado`), con un solo banco activo. Termina al enviar un `40` del otro banco, un `70` o al llegar un SMS de autenticación fallida de ese banco. Un SMS de autenticación correcta hecha desde otra app también abre sesión.
  - La sesión sale de las operaciones guardadas: sobrevive a cerrar la app y al bloqueo con huella.
  - `Clave incorrecta` en respuesta a un `40` propio bloquea ese banco hasta que se guarde la clave de nuevo.

## 3. Codificación de parámetros

Los parámetros `a*b*c` no viajan en claro. Es una codificación de compatibilidad, no un cifrado. Método de referencia: `rDEl7rW1aN.code()`, con `Tz34gww2TFwrsuT8ukpL`, `YzK9tTZsYVnC5kFxSie`, `ytpPfHi93O3gQEyZrT9r`, `hKRRh7lnIpNaVleqsuo`, `kR22DOzTiB` y `EKB7Plln4L`.

1. Se separa por `*`.
2. Si algún trozo es numérico, se elige un entero aleatorio 0–99 (`cod_ram`) que sale como prefijo de dos dígitos; si ninguno lo es, el prefijo es `00`.
3. Cada trozo numérico sale como `<longitud de 2 dígitos><valor>`:
   - Una tabla de 100 valores se obtiene leyendo `crude_code` en bloques de 8 bits, desplazados por los dígitos de `code_version` ([apéndice B](#apéndice-b-constantes-de-codificación)). Al acabar `code_version`, el índice vuelve a cero; el bytecode lo confirma y JADX omitía esa rama.
   - Cada par de dígitos se sustituye por su entrada de la tabla; si la longitud es impar, el último dígito se sustituye aparte.
   - El resultado se combina por XOR, como `Long`, con la tabla recorrida desde `cod_ram`. Al reiniciarse, esa secuencia salta la última entrada: el original lo hace así y hay que conservarlo.
4. Cada trozo de texto (descripción, usuario Nauta, importe decimal) sale como `00` seguido del código de dos dígitos de cada carácter ([apéndice C](#apéndice-c-diccionario-de-texto)). Un carácter fuera del diccionario no se puede enviar.

NeoTransfer: `protocol/ParameterCodec.kt`. Prueba: `core/src/test/resources/codec-vectors.tsv`, 1.400 vectores (14 entradas con las 100 semillas) generados con el código de referencia de TM (`ProtocolContractTest`).

## 4. Operaciones en alcance

### 4.1 Origen y moneda

**[TM]** El origen viaja dentro de cada operación como `<monedaTarjeta>*<cuentaOrigen>`; TM nunca envía un servicio previo para «seleccionar» una tarjeta.

- BPA (`Agg55uYctq:62-101`): CUP o USD → `1` o `3` con `0000`, la cuenta predeterminada de esa moneda. «Mis cuentas» o «Insertar cuenta» → `0` con los 16 dígitos.
- BANDEC (`Agg55uYctq:150-166,205`): no hay selector de moneda. El interruptor «Tipo de cuenta a operar» apagado deja `0*0000`; encendido envía `0` con la tarjeta.

**NeoTransfer** (`Source.forCard`): con una sola tarjeta de ese banco usa la cuenta predeterminada; con varias, envía la tarjeta explícita. `monedaTarjeta` es siempre `0` en BANDEC y con tarjeta explícita.

### 4.2 Formatos

Parámetros en orden, antes de codificar (§3). Importes con punto decimal, sin separador de miles y con dos decimales como máximo.

| Operación | Servicio | BPA (`01`) | BANDEC (`02`) | Evidencia [TM] |
|---|---|---|---|---|
| Autenticar | 40 | `01*<clave4>` | `02*<clave5>` | `lzGEfceoJu:45,56`; `TGFziPh3KJ:45,56` |
| Desconectar | 70 | `*444*70#` | igual | `R7PmVxXLJZC:338` |
| Saldo | 46 | `<1\|3\|0>*<0000\|tarjeta>` | `*444*46#`, sin parámetros ni versión | `ix4mhKGJty:53-58`; `R7PmVxXLJZC:58-59,355-372` |
| Últimas operaciones | 48 | `0*<1\|3\|0>*<0000\|tarjeta>` | `0*<1\|0>*<0000\|tarjeta>`; `1` = cuenta actual | `h3sd43nvje:53-58`; `gaJlF6dT3z:50-52`, `DQIrxODoMU:57-92` |
| Transferencia | 45 | `<destino16>*<importe>*<monImporte>*<monTarjeta>*<origen>*<móvil\|0000>*<enviarMiMóvil>*<mensaje\|0000>*<enviarSaldo>` | igual, con `monImporte` y `monTarjeta` a `0` | `iN1lHDKE5h:612`; `ZDkigMAIBm:450-456,533` |
| Pago QR dinámico | 30 | `01*<clave>*<id>*<importe>*<idMoneda>*<monTarjeta>*<proveedor>*<aux>*<origen>[*<móvil\|0>]` | igual con `02` | `r7Rinp9ooL:248-298` |
| Pago QR estático | 31 | `…*<aux>*<descripción\|0000>*<origen>[*<móvil\|0>*<idQR>]` | igual | `r7Rinp9ooL:304-371` |
| Electricidad | 41 | `<factura 11\|13>*0*<moneda>*<origen>` | `<factura>*0`, o `<factura>*0*<tarjeta>` | `xSwGh0MTOg:102,117`; `Q3Nvf439ab:102-105,120` |
| Teléfono fijo | 42 | `<factura 14\|15>*<importe>*<moneda>*<origen>` | `<factura>*<importe\|0 = total>[*<tarjeta>]` | `u4QMc0S0ZHo:154-161,171`; `iQte15zdZs:162-173,189` |
| Agua | 19 La Habana · 51 resto · 21 Varadero | `<región 1-3>*<cuenta>*<importe>*<tipo>*<monTarjeta>*<origen>` | 19 y 21: `<región>*<cuenta>*<importe>*<tipo>*0*<origen>`; 51 sin ese `0` (cinco campos) | `zOs55UzH6z:210-235`; `T4RQ5wDJDt:203-229` |
| Recarga Nauta | 59 | `<usuario>*<tipo>*<importe>*<monTarjeta>*<móvil\|0000000>*<origen>*1` | igual, moneda `0` | `PqTzgaEYF6:68-72,216-240,351-366` |
| Nauta Hogar: recarga / deuda | 84 / 86 | como 59, con sufijo `*2` / `*3` | igual | `PqTzgaEYF6:242-288` |
| Recarga móvil | 54 | `<móvil 8\|10>*<importe>*<monTarjeta>*<origen>` | igual, moneda `0` | `SjZHfPJPwX:262-284,307` |

Detalles:

- **Transferencia.** `monImporte` es la moneda del importe. `móvil` es el «Móvil a confirmar» que recibe el aviso. `enviarMiMóvil = 1` hace que el destinatario reciba el número del ordenante («El destinatario recibe mi número de móvil»). `enviarSaldo = 1` *omite* el saldo restante en el SMS: la casilla de TM dice «Omitir saldo restante en el SMS» (`strings.xml`, `hint_no_enviar_saldo`). NeoTransfer envía `enviarMiMóvil = 0`, mensaje `0000` y `enviarSaldo = 0`, para que el comprobante traiga el saldo.
- **Pago QR.** BPA filtra una clave de 4 dígitos; BANDEC admite 4 o 5 (`r7Rinp9ooL:94-97,612`). NeoTransfer envía la clave guardada. Con `aux` que contiene `11200301`, el `aux` sale como `0` y el móvil omitido como `0000` (`r7Rinp9ooL:224-238`). El móvil y el `idQR` solo se añaden con un móvil indicado o un `idQR` del `extra` (§6). Recorte del identificador en §6.
- **Electricidad.** El `0` es el importe: el banco cobra la factura completa. En BANDEC, la tarjeta solo se añade con el interruptor de cuenta encendido en TM, o con tarjeta explícita en NeoTransfer; lo mismo en el teléfono fijo.
- **Teléfono fijo.** El identificador es el de la factura (14 o 15 caracteres), no el número de teléfono. En BANDEC, importe `0` paga el total.
- **Agua.** En BPA el quinto campo es `monedaTarjeta`, no un `0` fijo. `tipo` `1` es pago total y `3` parcial (`T4RQ5wDJDt:56-66`); el importe arranca en `0` (`:111`). NeoTransfer paga el total con los valores iniciales de los formularios de TM: importe `0.0` en BPA y `0` en BANDEC, `tipo` `1` en La Habana y Varadero y `0` en el resto del país.
- **Nauta.** `tipo` es `1` para cuentas `@nauta.com.cu` y `2` para `@nauta.co.cu`. El usuario va sin dominio, sin espacios ni `@` y con 20 caracteres como máximo (`PqTzgaEYF6:320-335`). El importe sale como `float` de Java (`100.0`) y debe ser al menos 1 (`:361`). El móvil omitido es `0000000`.
- **Recarga móvil.** El servicio `88` (en MLC) queda fuera de alcance.

NeoTransfer: `protocol/Requests.kt`. Pruebas en `ProtocolContractTest`: orden de parámetros de cada operación, `core/src/test/resources/service-oracle.tsv` (41, 42, agua y 54 de ambos bancos con tres semillas) y los 800 vectores QR.

### 4.3 Cubacel

**[TM]** Sin `444` y sin codificar (`LALCrDPvhM:40-75`, `C8l2t8799rL:76-89`):

| Uso | Código |
|---|---|
| Saldo | `*222#` |
| Bonos | `*222*266#` |
| Datos | `*222*328#` |
| Voz | `*222*869#` |
| SMS | `*222*767#` |
| Transferir saldo | `*234*1*<móvil 8>*<clave de transferencia 4>*<monto entero>#` |
| Planes y paquetes | `*133#` (menú interactivo) |

La respuesta es el propio texto USSD; no hay SMS de `PAGOxMOVIL`. TM tiene además `*222*264#` (Plan Amigos), `*234*3#` (adelanto de saldo), `*111#` (pospago) y `*234*2*<nueva>*<repetida>#` (cambiar la clave de transferencia), que NeoTransfer no usa.

NeoTransfer: `protocol/Cubacel.kt`. La clave de transferencia se escribe para cada envío y no se guarda.

## 5. Servicios que NeoTransfer no envía

Ninguna operación de NeoTransfer produce `60`, `49`, `58`, `68` ni `69`; lo fija la prueba `no request ever produces 60, 49, 58, 68 or 69`.

- **60, asociar o cambiar tarjeta.** `60 <tarjeta16>` codificado. En TM es siempre una acción manual y nunca precede a `46`, `48` ni `45`:
  - BANDEC, pestaña Sesión: «Cambio de tarjeta — Tarjeta RED a operar» (`rvxiiV3tYu:43-51,89-95`), con la confirmación «Desea utilizar la cuenta: X» (`o9swVUfDl4q:51,75-78,87`).
  - BPA, Configuración: «Asociar Cuentas — Vincular otra cuenta bancaria» (`f5AFyaj0rtx:66,145-149`; `Ittht2aX4N:51,75-78`).
  - **[SMS]** Con la tarjeta ya asociada, el banco responde `Error en la asociacion de tarjeta. Banco Bandec: Esta tarjeta ya esta asociada a un celular`. Qué hace con otra tarjeta: **[no verificado]**.
- **58, todas las cuentas.** `*444*58#`, manual (`R7PmVxXLJZC:502-519`, `kmO9Md6JHr:517-524`).
- **68, eliminar registro.** El camino activo es `*444*68*01#` o `*444*68*02#`, sin clave, sin codificar y sin versión (`f5AFyaj0rtx:334-351`; `X7obECfkKf:57,226-243`). El formulario con clave (`sxbhjZQZxo`) no se abre desde ningún sitio.
- **69, cambiar la clave.** `69 <claveNueva>`, de 4 dígitos en BPA (`PQmKwgxZd1:51,60`) y 5 en BANDEC (`FtAKxvNjrD:53,62`). La repetición se valida en el teléfono; la clave actual no se envía.
- **49, registro.** Lo hace Transfermóvil. Su SMS de respuesta trae la clave y el PIN de la tarjeta en claro; NeoTransfer nunca guarda ese texto.

## 6. Códigos QR y enlaces

**Pago en JSON** (`LgiFGRpiEl:279-390`):

```json
{"id_transaccion":"…","importe":123.45,"moneda":"CUP","numero_proveedor":"…","version":1}
```

- Claves opcionales: `titulo` (si tiene 40 caracteres o más, se recorta a 19), `descripcion` (si no está vacía, es de solo lectura) y `extra`.
- `importe` admite notación científica. `moneda` se busca por contenido y en este orden: `CUP` o `1`, `CUC` o `2`, `USD` o `3`. `version`, si no está vacía, sustituye a `aux` (por defecto `1`); no es la versión del cliente.
- Importe `0` → servicio 31 con importe editable. Importe positivo e `id_transaccion` que contiene `ESTATICO` → 31 con importe fijo. El resto → 30.
- **[Pública]** El generador citado escribe el JSON con comillas simples; TM lo lee con `org.json`, que las admite.

**`extra`** (`LgiFGRpiEl:130-260,423-450`):

- Base64 de un AES/CBC/PKCS5. La clave son los 16 primeros caracteres hexadecimales en minúsculas del SHA-256 de `numero_proveedor`; el IV, lo mismo sobre `id_transaccion`.
- Contenido: `descripcionHint,regex,fechas,idQR`. Las fechas son `ddMMyyyyddMMyyyy` inclusivas y `00000000` deja abierto ese extremo; fuera de rango el QR se descarta como vencido o no disponible (el bytecode compara `> 0 && == 16`, `fallback:568-572`). `regex` se lee y no se usa.
- Un `extra` que no se puede descifrar o con menos de cuatro campos se **ignora**: la excepción se captura y el pago sigue (`fallback:520,531-551,689-703`).
- Un `idQR` no vacío hace que el pago envíe también móvil e `idQR` (`r7Rinp9ooL:248-274,315-345`).

**Pago en texto plano** (`r7Rinp9ooL:554-604`): exactamente cinco campos separados por coma, `<id>,<importe>,<moneda>,<proveedor>,<aux>`, y los marcadores `TRANSFERMOVIL_ETECSA` y `PAGO_EXTERNO` dentro de algún campo. Una muestra real: **[no verificado]**.

**Tarjeta para transferir**: `TRANSFERMOVIL_ETECSA,TRANSFERENCIA,<tarjeta>,<móvil>,` (`ZDkigMAIBm:797-830`). TM hace `split(",")`: el campo 2 es la tarjeta, que solo usa si tiene 16 caracteres, y el 3 el móvil a confirmar. Acepta móviles de 8 o 10 dígitos y a los de 10 les quita los dos primeros sin mirarlos.

**Enlaces** (`S9ycHcoxAzy:831-940`): `transfermovil://tm_compra_en_linea/action?…` y `https://transfermovil.app/action?…`, con `id_transaccion`, `importe` (la coma se convierte en punto), `moneda`, `numero_proveedor` (entero) y `descripcion` opcional. El código acepta también `http`, pero el manifiesto solo registra esos dos. El pago se convierte en JSON y pide el banco entre BPA, BANDEC, METROPOLITANO y MITRANSFER.

**Recorte del identificador** (`r7Rinp9ooL:46,275-279,346-365`): si la llamada codificada llega a 130 caracteres, `id_transaccion` se reduce a sus últimos 15; en un 31 sin móvil ni `idQR`, a los últimos 8.

**Lectores**: TM lee con ML Kit (`OJ3XxacXOT`), que no valida el contenido y lo pasa al formulario (`OJ3XxacXOT:737-760`). El lector ZXing (`f6Y2Wmtg6jQ`) sí valida, pero ninguna clase lo instancia.

**NeoTransfer** (`qr/QrCode.kt`, `qr/QrPayment.kt`; prueba `QrCodeTest`):

- Acepta JSON, texto plano y los dos enlaces (también con `http`), y rechaza un enlace con campos repetidos.
- Ignora el `extra` ilegible como TM y aplica las fechas.
- Lee el código de tarjeta tolerando mayúsculas, espacios y comas de más, y también una tarjeta de 16 dígitos sola. Un móvil de 10 dígitos solo vale si empieza por `53`.
- Recorta el identificador como TM y, si la llamada sigue pasando de 182 caracteres, usa el modo parcial (§1.3).
- El QR de cobro de una tarjeta propia (`CardQr.encode`) usa el mismo formato de tarjeta, legible por TM.

## 7. SMS de resultado

### 7.1 Qué hace Transfermóvil con ellos

- **Tiempo real.** El receptor `SMS_RECEIVED` del manifiesto solo escribe en el log (`X9ZiE8XZNKy:17-21`). La versión general registra su receptor en `LocalBroadcastManager`, que nunca recibe SMS del sistema (`S9ycHcoxAzy:641-647`): no reacciona a ningún SMS. La versión de agente muestra el texto crudo de `PAGOxMOVIL` en un diálogo, sin interpretarlo (`IHVTCBmiLV:128-129`, `rDEl7rW1aN:120-143,1031-1042`).
- **Comprobantes.** Bajo demanda lee toda la bandeja con `address='PAGOxMOVIL'` (`HzjedYqYeh:35-61`), la analiza con `g0xM9kQCsqu.parse` y deduplica por `idTransaccion` y tipo de servicio (`rDEl7rW1aN:501-528`). Los campos salen de la palabra que sigue a cada etiqueta (`g0xM9kQCsqu:1677-1689`) o de posiciones fijas.
- **Puerta** (`g0xM9kQCsqu:117`): solo entra en la ruta bancaria un SMS que contiene «Banco» y alguna de estas frases: «El pago», «La Transferencia», «La recarga se», «La amortizacion de credito», «Id Transaccion:», «Giro Postal», «Id Compra:», «PIN de combustible», «caja extra» o «turno». El banco se deduce del nombre: «Popular de Ahorro(s)» es BPA y «Bandec» es BANDEC (`:226-246`).
- **Bienvenida.** «Bienvenido al sistema TRANSFERMOVIL» no es un marcador de éxito: de ahí salen el teléfono y la versión (`:1625-1675`).
- **Lo que TM no lee.** Saldos, autenticaciones, «solicitud en proceso», transferencias recibidas, avisos impersonales, «Fallo…», «Error…» y últimas operaciones devuelven `null` (`:223`). TM tampoco guarda saldos: su tabla `MCBank` solo tiene agencia, alias, cuenta, identificador, móvil y moneda. Todo lo que NeoTransfer hace con esos mensajes es lógica propia, apoyada en lo observado (§7.3).

### 7.2 Recepción en NeoTransfer

- `SmsReceiver` despierta la app, aunque esté cerrada, cuando llega un SMS de `PAGOxMOVIL`. El controlador lee la bandeja desde el último `_id` guardado, inclusive; mientras el proceso vive, un observador de `content://sms` cubre las escrituras tardías (`app/src/main/kotlin/dev/duardo/neotransfer/platform/SmsPlatform.kt`, `AppController.ingest`).
- La primera vez importa toda la bandeja de `PAGOxMOVIL` sin notificar: la actividad se reconstruye al instalar.
- Orden: `date_sent` (marca del SMSC). **[SMS]** `PAGOxMOVIL` llega siempre por el mismo SMSC, sin marcas anómalas en 251 mensajes (un solo valor negativo, −0,13 s). ETECSA, Cubacel, Nauta, BANDEC y ENZONA llegan por otros SMSC cuyo `date_sent` va unas 11 horas (≈39.600 s) por detrás de la recepción; NeoTransfer solo lee `PAGOxMOVIL`.
- Los SMS son datos internos: la app no muestra su texto. Del SMS de registro no se guarda el cuerpo.

### 7.3 Plantillas observadas

**[SMS]**, n = 251. `<máscara>` es una tarjeta con dígitos ocultos (por ejemplo `9204XXXXXXXX1234`), `<ref>` la referencia alfanumérica. Espacios y saltos de línea varían; el lector tolera tildes, mayúsculas, espacios repetidos y etiquetas con o sin dos puntos (`sms/SmsParser.kt`; prueba `SmsTemplateTest`, con textos sintéticos).

| Tipo | n | Plantilla | `SmsMessage` |
|---|---|---|---|
| Autenticación BPA | 33 | `Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil` (a veces con un bloque `INFORMACION:` al final) | `Authenticated` |
| Autenticación BANDEC | 40 | `… en el Banco Bandec con la cuenta <máscara>, puede comenzar …` (una vez con la cuenta vacía) | `Authenticated`, con la tarjeta de la sesión |
| Autenticación fallida | 6 | `Banco Bandec: Clave incorrecta Error de autenticacion, intente nuevamente…`; `Banco Popular de Ahorro: LA AUTENTICACION HA FALLADO Error de autenticacion…`; sin banco: `vuelva a intentarlo, si persiste el problema, contacte con ETECSA Error de autenticacion…` | `AuthenticationFailed` |
| Ocupado | 2 | `Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion.` | `Busy` |
| Saldo BPA | 24 | `Banco Popular de Ahorro:  La consulta de saldo fue completada. Saldo Disponible: CR <importe> CUP` | `Balance` |
| Saldo BPA en tabla | 7 | `Nombre Cuenta;Nro Cuenta; Saldo Disponible; Moneda` y filas `; <rótulo> ; <importe>; <moneda>` (rótulos como `Ahorro` o `Dep.USD`, no tarjetas) | `Balance`, sin tarjeta |
| Saldo BANDEC | 45 | `Banco Bandec La consulta de saldo fue completada. Cuenta;Saldo Contable;Saldo Disponible;Moneda` y `<máscara>; CR <importe> ; CR <importe> ;CUP \|` (siempre una fila) | `Balance` |
| Saldo fallido | 2 | `Fallo la consulta de saldo. vuelva a intentarlo, si persiste el problema, contacte con su proveedor de servicios.` | `Rejected` |
| Últimas operaciones BANDEC | 5 | `Banco Bandec Ultimas operaciones. Fecha;Servicio;Operacion;Monto;Moneda;NoTransaccion` y hasta 10 filas `d/m/aaaa;<servicio> Ref: <ref>;Db\|Cr;<importe>;CUP;` separadas por `\|` (a veces con texto corrupto) | `History` |
| Transferencia enviada | 8 | `Banco <banco>:  La Transferencia fue completada. Fecha: d/m/aaaa Beneficiario: <máscara> Ordenante: CUP\|<máscara> Monto: <importe> CUP Nro. Transaccion: <ref> [Saldo restante: CR <importe> CUP]` (`Ordenante: CUP` = cuenta predeterminada) | `TransferSent` |
| Aviso impersonal de transferencia | 7 | `Se ha realizado una transferencia a la cuenta <16 dígitos> de <importe> CUP. Nro. Transaccion <ref>. Fecha: d/m/aaaa.` (5 copian un comprobante; 2 llegan solos) | `TransferNotice` |
| Transferencia recibida | 22 | `El titular del telefono <móvil> le ha realizado una transferencia a la cuenta <16 dígitos> de <importe> CUP. Nro. Transaccion <ref>. Fecha: d/m/aaaa.` (sin banco ni saldo; importe sin separador de miles; 2 antiguos sin `Fecha`) | `TransferReceived` |
| Transferencia fallida | 1 | `Fallo la transferencia. Banco Popular de Ahorro: LA CUENTA NO ESTA ASOCIADA A UNA TARJETA MAGNETICA` | `Rejected` |
| Pago QR o compra | 5 | `Banco <banco>: Pago completado. Fecha: … Entidad: <comercio> Id Compra: <id> Importe: <importe> CUP Importe pagado: <importe> CUP No. Transaccion: <ref> Saldo disponible: CR\|DB <importe> CUP` (BPA usa `DB`) | `PaymentCompleted` |
| Pago de servicio | 15 | Nauta Hogar: `La cuenta Nauta Hogar: <cuenta> ha sido pagada con <importe> CUP. Monto Pagado: <importe> CUP. Id Transaccion: <ref>. Saldo Restante: DB <importe> CUP.`; recarga Nauta: `La cuenta :<cuenta> ha sido recargada con …`; recarga móvil: `La recarga se realizo con exito. … Monto Pagado … Telefono … Id transaccion … Saldo Restante: DB <importe>.`; sello del timbre | `ServicePaid` |
| Copia de pago de servicio | 7 | `Se ha pagado la cuenta nauta hogar <cuenta> con <importe> CUP. Nro. Transaccion <ref>` · `Se ha recargado la cuenta …` | `ServiceNotice` |
| Pago de servicio fallido | 9 | `Fallo la recarga del movil <móvil> alcanzo el monto limite de recarga permitido en <n> dias (<límite> CUP), puede recargar posterior al dia dd-mm-aaaa.` · `Fallo el pago de la cuenta Nauta Hogar, la cuenta <cuenta> es incorrecta.` · `… El monto tiene que ser mayor que <mínimo>` · `… no tiene deuda` | `Rejected` |
| Cierre de plazo fijo fallido | 3 | `Banco Popular de Ahorro Fallo el cierre del deposito a plazo fijo de la cuenta …` (fuera de alcance) | `Rejected` |
| Recarga de saldo recibida | 4 | `Usted ha recibido una recarga de saldo movil por un monto de <importe> CUP del numero <móvil>…` (saldo del teléfono, no del banco) | `MobileBalanceReceived` |
| Registro completado | 1 | `… La operacion de registro fue completada. Bienvenido a Transfermovil. Clave de Autenticacion: … El PIN asociado a su tarjeta … es …` | `Registration` |
| Error de asociación | 5 | `Error en la asociacion de tarjeta. Banco Bandec: Esta tarjeta ya esta asociada a un celular` (4) · `Error en la asociacion de tarjeta. Banco Popular de Ahorro: LA CUENTA NO ESTA ASOCIADA A UNA TARJETA MAGNETICA` (1) | `Rejected` |

Sin muestra real, el lector reconoce los pagos de electricidad (`El pago de la factura de electricidad fue completado`, `g0xM9kQCsqu:578-590`), teléfono fijo (`El pago de la factura telefonica fue completado`, `:539-577`) y agua (`El pago de la factura del agua fue completado.`, `:248-267`) con las plantillas del analizador de TM, y las últimas operaciones de BPA con el formato de BANDEC. Su forma real: **[no verificado]**.

Rechazos: «Fallo…», «Error…» y «vuelva a intentarlo…» cierran la operación como rechazada, con el motivo del banco en lenguaje normal. «Usted tiene una solicitud en proceso…» se interpreta como que la última solicitud enviada no se ejecutó; para dinero, esa lectura: **[no verificado]**.

### 7.4 Saldos y duplicados

**[SMS]**

| Mensaje | BPA | BANDEC | Forma |
|---|---|---|---|
| Transferencia enviada | 1 de 1 | 6 de 7 | `Saldo restante: CR x` |
| Pago de servicio | 14 de 14 | 1 de 1 | `Saldo Restante: DB x` (el sello no lleva marcador) |
| Pago QR | 1 de 1 (`DB`) | 4 de 4 (`CR`) | `Saldo disponible: …` |
| Transferencia recibida | 0 de 10 | 0 de 12 | — |

- En comprobantes de servicio y QR, `DB x` es el saldo `x`, no una deuda: seis comprobaciones contra el saldo siguiente cuadran.
- 2 de los 12 comprobantes de BANDEC con saldo traían el saldo **anterior** al débito (una transferencia y un QR), confirmado con las últimas operaciones y el saldo siguiente.
- En BANDEC el saldo contable va por detrás del disponible tras un abono (5 casos). NeoTransfer muestra el disponible.
- Duplicados: un comprobante idéntico repetido a los 10 s, y 12 pares de comprobante y aviso impersonal con la misma referencia, separados por 0 a 5 s. NeoTransfer cuenta un movimiento por referencia y nunca atribuye un aviso impersonal que llega solo.

NeoTransfer: `ledger/Activity.kt` (movimientos y proyección de saldo; ver [arquitectura](arquitectura.md#saldo)).

## 8. Tiempos medidos

**[SMS]** Retraso de entrega, recepción menos marca del SMSC, en segundos. Solo mide la red, no el procesamiento de la plataforma.

| Grupo | n | Mediana | p90 | Máximo | Más de 30 s |
|---|---|---|---|---|---|
| Todos | 251 | 6,2 | 85,9 | 862,6 | 40 (16 %) |
| Autenticación BPA | 33 | 4,1 | 63,3 | 413,7 | 4 |
| Autenticación BANDEC | 40 | 14,1 | 85,9 | 342,6 | 8 |
| Saldo BPA | 31 | 4,4 | 122,2 | 385,3 | 5 |
| Saldo BANDEC | 45 | 6,9 | 83,9 | 547,5 | 9 |
| Dinero saliente | 28 | 3,6 | 35,4 | 160,7 | 4 |
| Transferencia recibida | 22 | 3,3 | 318,3 | 862,6 | 5 |

- Hubo 15 llegadas fuera de orden en 251 (6 %). En 12, el resultado llegó antes que el SMS de la autenticación que lo precedía, con 4 a 115 s de adelanto.
- Android no registra los USSD enviados, así que el tiempo envío→SMS no se puede medir. Las ráfagas de resultados consecutivos salen del SMSC cada 9 a 15 s: la plataforma procesa en serie, unos 10 s por operación.
- «Ocupado» apareció 2 veces en 79 intentos de autenticación.

Con esto, NeoTransfer (`ledger/Ledger.kt`) espera el acuse USSD hasta 45 s, deja una operación en «Sin respuesta» a los 15 minutos sin SMS (el máximo observado fue 14,4) y sigue aceptando un resultado tardío durante 2 horas.

## Apéndice A: clases de Transfermóvil citadas

| Clase | Qué es |
|---|---|
| `S9ycHcoxAzy` / `IHVTCBmiLV` | Actividad principal general / de agente; ambas heredan de `rDEl7rW1aN` |
| `rDEl7rW1aN` | Base: codificador `code()`, envíos USSD, lectura de la bandeja |
| `FBsQzP3Zfj` | Utilidades: diálogo «Confirmar» y `ACTION_CALL`, sesión local, accesos directos |
| `AppConfig` | Configuración: código 444, versión, banco activo, hora de autenticación, `activarussdparcial` |
| `X9ZiE8XZNKy` | Receptor `SMS_RECEIVED` del manifiesto (vacío) |
| `HzjedYqYeh` / `g0xM9kQCsqu` | Lectura de la bandeja de `PAGOxMOVIL` / analizador de SMS a comprobante |
| `Agg55uYctq` / `DQIrxODoMU` | Bases de formulario con selector de origen (general / BANDEC «cuenta actual») |
| `pAzq93vIl3` / `MX9gh9mLxd` / `O7xyZJJBFWg` | Modo parcial / envío con `sendUssdRequest` / accesibilidad que pulsa «OK» |
| `s684VE6Dm0` | Pestañas BANDEC: Sesión `rvxiiV3tYu` (40, 70 y 60), Consultas `R7PmVxXLJZC`, Operaciones `bLlRBTTLue`, Servicios contratados `Kgii4NH4Ce`, Configuración `X7obECfkKf` |
| `kChOHNVmzz` | Pestañas BPA: Sesión `p97uirGL7T` (40 y 70), Consultas `kmO9Md6JHr`, Operaciones `voNSkP3P8q`, Configuración `f5AFyaj0rtx` |
| `TGFziPh3KJ` / `lzGEfceoJu` | Autenticar (40) BANDEC / BPA |
| `ix4mhKGJty` | Saldo (46) BPA |
| `gaJlF6dT3z` / `h3sd43nvje` | Últimas operaciones (48) BANDEC / BPA |
| `o9swVUfDl4q` / `Ittht2aX4N` | Servicio 60: «Cambio de Cuenta» BANDEC / «Asociar cuentas» BPA |
| `FtAKxvNjrD` / `PQmKwgxZd1` | Cambio de clave (69) BANDEC / BPA |
| `ZDkigMAIBm` / `iN1lHDKE5h` | Transferencia (45) BANDEC / BPA |
| `Q3Nvf439ab` / `xSwGh0MTOg` | Electricidad (41) BANDEC / BPA |
| `iQte15zdZs` / `u4QMc0S0ZHo` | Teléfono fijo (42) BANDEC / BPA |
| `T4RQ5wDJDt` / `zOs55UzH6z` | Agua (19, 51, 21) BANDEC / BPA |
| `PqTzgaEYF6` | Recarga Nauta (59), Nauta Hogar (84) y su deuda (86) |
| `SjZHfPJPwX` | Recarga móvil desde el banco (54) |
| `r7Rinp9ooL` ← `LgiFGRpiEl` | Pago QR o en línea (30, 31) ← lector del JSON y del `extra` |
| `UTk9UWZYxf` | Caja Extra (32) |
| `OJ3XxacXOT` / `f6Y2Wmtg6jQ` | Lector QR con ML Kit (el que se usa) / con ZXing (sin uso) |
| `LALCrDPvhM` / `C8l2t8799rL` | Consultas Cubacel / transferir saldo |

## Apéndice B: constantes de codificación

De TM 1.260416; las mismas están en `protocol/ParameterCodec.kt`.

```
code_version = "372653282298718711"
crude_code = "10000110101100011000100100100000110010001100000011100010100110010001010010100010111110001100010000011000010101100000000110001100101010100100011000000101110001100001100010001011110001100010100001000110000001101000101001001101001000001000010001100100011010010111001000110011110010001001100001000100000010000111001000110000101110100001111110011000001000110010000111110001100001111111000110011000101001100101000110001000111100011000101011101011111100111010100010111101000110000110011000110011100011000100111000101000011100010100111000011101100011000010101010000010101000100010100011001010101101000110001011101100011001001101101001111100011000000100010001100100010110101000110110000110000101000100011000000010100000010010001101011011100010110001110001001011100000110110001100001000011001011010100101100010001100100011110100011000011000110001100101010110101011110001100010000101000110001001101010010001001110001000010110110001100100111010001101101000110010010110001000110101000011110110000011101000110001001001100100010010000001111000110010011101110001100000001011000110000100011001111101000110000010010100011000110111100101100101011001100000010011000110000000001000100111100011000101101000100100000100010010101000100011100011000001101110001110011000101001"
```

## Apéndice C: diccionario de texto

Carácter → código de dos dígitos, de la clase `EKB7Plln4L`.

| Car. | Código | Car. | Código | Car. | Código | Car. | Código |
|---|---|---|---|---|---|---|---|
| `.` | 15 | `-` | 85 | `_` | 58 | `0` | 37 |
| `/` | 11 | `,` | 29 | `%` | 67 | `&` | 20 |
| `a` | 59 | `b` | 00 | `c` | 82 | `1` | 89 |
| `A` | 05 | `B` | 54 | `C` | 60 | `á` | 88 |
| `@` | 64 | `d` | 51 | `e` | 93 | `h` | 50 |
| `2` | 41 | `D` | 57 | `E` | 65 | `F` | 61 |
| `é` | 91 | `#` | 08 | `g` | 30 | `f` | 46 |
| `i` | 97 | `3` | 80 | `G` | 83 | `H` | 13 |
| `I` | 55 | `í` | 53 | `;` | 56 | `j` | 76 |
| `k` | 25 | `l` | 42 | `4` | 66 | `J` | 73 |
| `K` | 17 | `L` | 38 | `ó` | 32 | `m` | 27 |
| `n` | 92 | `o` | 12 | `ñ` | 03 | `5` | 01 |
| `M` | 06 | `N` | 49 | `O` | 18 | `Ñ` | 33 |
| `p` | 98 | `q` | 23 | `r` | 31 | `6` | 94 |
| `P` | 44 | `Q` | 35 | `R` | 34 | `ú` | 87 |
| `s` | 19 | `t` | 45 | `u` | 81 | `7` | 62 |
| `S` | 22 | `T` | 40 | `U` | 10 | `v` | 96 |
| `w` | 69 | `x` | 75 | `8` | 24 | `V` | 14 |
| `W` | 70 | `X` | 74 | `y` | 09 | `z` | 90 |
| espacio | 78 | `9` | 72 | `Y` | 71 | `Z` | 07 |
| `Á` | 99 | `É` | 86 | `Í` | 47 | `Ó` | 36 |
| `Ú` | 39 | `ü` | 77 | `Ü` | 95 |  |  |
