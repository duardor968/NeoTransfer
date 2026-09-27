# Protocolo de Transfermóvil (referencia para NeoTransfer)

Soporte activo: BPA, BANDEC, BANMET, MiTransfer personal, Clásica personal y servicios Cubacel. BFI, Clásica empresarial, el perfil Agente, Bulevar, MiBoleto, las reservas de visitas/recorridos OFA y Votomóvil se retiraron por decisión de alcance. Los aportes OFA94 de BANDEC, BANMET y MiTransfer personal permanecen. Sus identificadores históricos se conservan únicamente para lectura/importación; no habilitan formularios ni ejecución.

CUC no se ofrece para productos ni operaciones nuevas, conforme a su [retirada de circulación (Decreto-Ley 17/2020, artículo 2)](https://www.gacetaoficial.gob.cu/sites/default/files/goc-2020-ex68.pdf#page=4). Se conserva para interpretar datos históricos. Retirar una opción no renumera el contrato: CUP sigue siendo `1` y USD `3` en la codificación bancaria; el identificador histórico CUC sigue siendo `2`.

Extraído el 2026-09-21 de fuentes públicas y, para lo que ninguna cuenta, del APK Transfermóvil 1.260416 (versionCode 162, paquete `cu.etecsa.cubacel.tr.tm`). Lo marcado **[no verificado]** se confirma con la SIM en la primera rebanada (autenticación → saldo → transferencia → pago QR).

Fuentes públicas: [rodnye/ussd-codes-cuba](https://github.com/rodnye/ussd-codes-cuba) · [Códigos USSD Transfermóvil, Portal de Holguín](https://www.holguin.gob.cu/es/codigos-ussd-transfermovil) · [Guía BANMET, Cubadebate](http://agendaeconomica.cubadebate.cu/guia-completa-asi-se-usan-los-codigos-de-marcacion-para-el-banco-metropolitano/) · [Códigos BANDEC, Directorio Cubano](https://www.directoriocubano.info/acontecer/asi-puedes-usar-transfermovil-sin-la-app-todos-los-codigos-de-bandec-paso-a-paso/) · [Registro, sesión y SMS, Cubatramite](https://cubatramite.com/pagar-en-linea-desde-la-aplicacion-transfermovil/) · [QR y deep link de comercio, roberticovinales/transfermovil](https://github.com/roberticovinales/transfermovil/blob/main/crearpago.php).

## 1. Transporte

- Formato ordinario de una operación bancaria: `*444*<servicio>*<parámetros codificados>*<versión>#`. El modo parcial divide una operación en varios mensajes USSD (§1.3).
  - `444` es el código de acceso a la plataforma (string `codigoAcceso`); `versión` es `1260416` (string `version_transfermovil`). Ambos viven en la tabla AppConfig (`codigo`, `number`) y la app puede actualizarlos desde el SMS de bienvenida ("Su número … tiene la versión X").
  - Envío normal: `Intent.ACTION_CALL` con `tel:*444*…*1260416%23` (el `#` va codificado). El marcador del sistema muestra la respuesta USSD.
  - Envío por API (Android 8+): `TelephonyManager.sendUssdRequest(código, callback, handler)`. La app lo usa para el modo "USSD parcial" (§1.3).
- Respuesta: USSD inmediato "Su solicitud está siendo procesada, espere un SMS…"; el resultado real llega por **SMS del remitente `PAGOxMOVIL`** (§5). No hacen falta datos móviles, solo red celular.
- Códigos "de menú" sin parámetros (`*444*46#`, `*444*45#`…): la red pregunta campo a campo por diálogos USSD. Son el catálogo público y el plan B manual desde cualquier teléfono; no se automatizan.

### 1.1 Sesión bancaria

- Autenticar: servicio `40`, parámetros `<banco>*<clave>`. Desconectar: `*444*70#` sin parámetros.
- La app guarda banco activo y hora de autenticación y da la sesión por válida **1 hora**; hay **un solo banco activo** a la vez. Caducidad real en el servidor: **[no verificado]**.
- Tras autenticar, las operaciones van **sin clave**, salvo el pago en línea (30/31), que la lleva siempre.

### 1.2 Bancos (`agencia`)

| Código | Entidad | Clave de autenticación |
|---|---|---|
| 01 | BPA | PIN de 4 dígitos |
| 02 | BANDEC | clave de 5 dígitos (el "PIN de tarjeta" de 4 es otro dato) |
| 03 | BANMET | 4 dígitos |
| 04 | Monedero MiTransfer | — |

### 1.3 USSD parcial (payloads largos)

Activado en el original por `activarussdparcial`. Se codifica la cadena completa una vez y se agrupan sus átomos separados por `*`; solo el primer átomo lleva la semilla. Cada grupo comienza por `*`. Se añade el siguiente átomo cuando `átomo.length + grupo.length + 32 <= 52`: la comparación omite el nuevo separador. El DEX confirma este comportamiento; **52 no es un límite real del paquete**, y un átomo individual nunca se divide.

`*444*110*<pp><nn>*<idsecuencia>*<servicio>*<agencia><parte>*<versión>#`

- `pp` = índice de la parte, `nn` = total de partes (2 dígitos cada uno); `idsecuencia` = minutos y segundos actuales (`m` o `mm` sin cero inicial + `ss`); `<parte>` empieza por `*`.
- Se envía la siguiente parte solo si la respuesta contiene literalmente "siendo procesada,". En la ruta Android 8+ **no hay reintentos**; la constante `MAX_FALLAS=3` no se utiliza. El último acuse sigue sin confirmar el pago.
- El argumento `MAX_PARAMETERS_PQUETE=4` tampoco participa en esta agrupación. NeoTransfer conserva los identificadores completos; usa paquetes para sobres de más de 130 caracteres y rechaza paquetes que todavía superen 130. Es una restricción conservadora del cliente, **no un máximo de red demostrado**. El original recorta identificadores antes del envío; NeoTransfer no reproduce ese cambio silencioso.
- Referencia: `pAzq93vIl3/MX9gh9mLxd`, contrastada con DEX. Pruebas locales: 800 vectores QR de ocho payloads y 100 semillas; 4.020 paquetes de referencia. Fixtures sintéticos en `core/src/test/resources/qr-vectors.tsv`.

## 2. Codificación de parámetros

La cadena `a*b*c` **no se envía en claro**. Método de referencia: `rDEl7rW1aN.code()`.

1. Se separa por `*`. Si todos los trozos son numéricos (caso normal):
   - Se genera un entero aleatorio 0–99 (`cod_ram`) que sale como prefijo de 2 dígitos.
   - Cada trozo se transforma con una clave derivada de dos constantes embebidas (`code_version`, `crude_code`, apéndice A): `crude_code` se lee en bloques de 8 bits, desplazados por los dígitos de `code_version`, y da una lista de bytes; con ella se sustituye cada par de dígitos del trozo (si es impar, el último dígito aparte) y el resultado se combina por XOR (como `Long`) con una secuencia de esa lista rotada a partir de `cod_ram`.
   - Salida: `<cod_ram><len1><t1>*<len2><t2>*…`, con `len` = longitud original del trozo en 2 dígitos.
2. Si hay trozos no enteros (descripción, nombre, correo, importe decimal): los numéricos siguen el paso 1; los de texto se sustituyen carácter a carácter con el diccionario de 2 dígitos (apéndice B) y llevan longitud `00`, no su longitud original. Sin trozos numéricos, el prefijo aleatorio también se sustituye por `00`.

Comprobación local del 2026-09-22: 1.400 vectores sintéticos del código de referencia, 14 entradas con todas las semillas 0–99. El DEX confirma que el índice de `code_version` vuelve a cero al final; JADX había omitido esa rama. La secuencia XOR del original omite la última entrada al reiniciarse: conservar ese comportamiento. Los vectores viven en `core/src/test/resources/codec-vectors.tsv`; no demuestran todavía aceptación por la red bancaria.

Clases fuente en el descompilado: `rDEl7rW1aN.code`, `Tz34gww2TFwrsuT8ukpL`, `YzK9tTZsYVnC5kFxSie`, `ytpPfHi93O3gQEyZrT9r`, `hKRRh7lnIpNaVleqsuo`, `kR22DOzTiB`, `EKB7Plln4L`. Java puro, unas 250 líneas: el port cabe en un fichero Kotlin.

Validación del port, en dos pasos:

1. En el PC: compilar esas clases tal cual (solo necesitan un stub de `Uri.encode` y de `BuildConfig.FLAVOR`) y generar vectores entrada → salida; el port debe reproducirlos fijando el mismo `cod_ram` (los 2 primeros dígitos de la salida).
2. Con la SIM: la plataforma acepta la cadena y llega el SMS.

## 3. Catálogo de operaciones (BPA y BANDEC)

Parámetros en orden, antes de codificar. Campos comunes:

- `cuentaOrigen`: `0000` = tarjeta por defecto del registro; o el número completo de otra tarjeta asociada, sin guiones.
- `monedaTarjeta`: 1 CUP, 2 CUC, 3 USD; cuenta explícita → 0. En la transferencia moderna y QR BANDEC va 0; el banco deduce la moneda de la cuenta.
- `movil`: en transferencia moderna el omitido es `0000`; la pantalla clásica usaba `00000000`. En QR el omitido puede ser `0` o `0000`, según la ruta descrita debajo.
- Importes con punto decimal y hasta dos decimales, sin separador de miles; validación y codificación contrastadas con el APK.

| Servicio | Operación | Parámetros | Notas |
|---|---|---|---|
| 40 | Autenticar | `<banco>*<clave>` | Ambos bancos |
| 70 | Desconectar | — | |
| 68 | Eliminar registro | `<banco>*<clave>` | |
| 49 | Registrarse | BPA `01*<tarjeta16>*<nombre>*<apellido1>*<apellido2>*<ci>` · BANDEC `02*<tarjeta16>*<nombreCompleto>*<vencimientoMMAA>` · BANMET `03*<telebanca10>*<nombre>*<apellido1>*<apellido2>*<ci>` | BANDEC renombra el campo interno `ci` a vencimiento: `wLHKkaC4Ha:68,94,129`. BANMET exige Telebanca de 10 dígitos iniciada en 95 (`RFBEnXW0Xr`). |
| 46 | Saldo | BPA/BANMET `<monedaTarjeta>*<origen>`; BANDEC `*444*46#` consulta la cuenta actual | BANDEC selecciona otra cuenta mediante 60 y después consulta 46; el motor debe correlacionar la respuesta con esa selección. |
| 48 | Últimas operaciones | BPA `0*<moneda>*<origen>`; BANDEC `0*<1 predeterminada/0 explícita>*<origen>`; BANMET `<filtro>*<moneda>*<origen>` | Contratos `BankCommands.recentOperations`, trazados en h3sd43nvje, gaJlF6dT3z y GhhAuUBVrI. |
| 58 | Todas las cuentas | — | |
| 62 | Consultar límites | `<monedaTarjeta>*<cuentaOrigen>` | |
| 61 | Cambiar límites | `<atm>*<pos>*<total>[*<moneda>][*<cuentaOrigen>]` | |
| 63 | Últimos pagos | `<tipoServicio>[*<cuentaOrigen>]` | |
| 45 | Transferencia moderna | `<destino>*<importe>*<monedaImporte>*<monedaTarjeta>*<origen>*<movil>*<enviarMiMovil>*<mensaje>*<enviarSaldo>` | BANDEC: ambas monedas 0. Omitidos origen/móvil/mensaje: `0000`; flags desactivados: 0 |
| 41 | Electricidad | BPA `<idFactura>*0*<moneda>*<origen>`; BANDEC `<idFactura>*0[*<origen>]` | Identificador de 11 o 13 dígitos; BANDEC omite origen cuando es el predeterminado |
| 42 | Teléfono | BPA `<idFactura>*<importe>*<moneda>*<origen>`; BANDEC `<idFactura>*<importe>[*<origen>]` | Identificador de factura de 14 o 15 caracteres, no el teléfono. BANDEC admite importe 0 para pago total |
| 51 (19/21) | Agua (BPA) | `<idRegionPago>*<cuenta>*<importe>*<idTipo>*0*<cuentaOrigen>` | |
| 67 | Gas | BPA/BANMET `<cuenta>*0*<monedaTarjeta>*<tipoPago>*<origen>`; BANDEC `<cuenta>*0*<tipoPago>[*<origen>]` | `FgpBLXKm6t:128`, `yiqT29TYTb:128`, `aiwIHVBe0c:128`; BANDEC omite origen predeterminado. |
| 43 | ONAT y sellos | ONAT `<modo>*<DPA>*<RC05/NIT>*<RC04/tributo>*<periodo>*<importe>*<monedaDébito>*<origen>`; sellos añade entidad antes de moneda | RC modo1 usa DPA/período0000; NIT modo2. Sellos admite denominación elegida o importe manual; su ruta parcial intercambia NIT/tributo. |
| 94 | Aporte OFA / Oficina del Historiador | Bancos: `<carnet>*<contribuyente>*<codigo>*<mes/año>*<importe>*<obligacion>*<monedaTarjeta>*<origen>`; MiTransfer sustituye los dos últimos campos por `<bolsa>` | El modelo es `ObligacionesOFA`, no ONAT genérico: `SZARzeWeX5:121`, `lQox6tgMlH:97`, `C6ZYln3x19W:120`. |
| 56 | Consulta ONAT | BANDEC completo `<rc05>*00000`; BANDEC/BANMET fiscal `<rc05>*<rc04a>` | RC05 de 11–16 dígitos; RC04A de 5. idsoBKS8L3:225–274 y hJhf3aumcr:210–256. |
| 47 | Consultar factura de servicio | `<tipo>*<factura>` | Tipos por banco en ServiceQueryOperations: gas BPA4/BANDEC5/BANMET11; aguaHabana BPA11 por DEX, otros AGUAHABANA; resto AGUAPROVINCIAS; Varadero AGUAVARADERO. |
| 54 | Recarga móvil | Pantalla compartida actual: `<movil>*<importe>*<monedaTarjeta>*<origen>`; BANDEC usa moneda 0 y conserva origen `0000` | `SjZHfPJPwX:269-283`, selector `GVEefrRz76`. La rama 88 no se publica: `array_tipo_recarga` solo ofrece un ítem. La pantalla BANDEC anterior `D6YZ7ruA6K` enviaba moneda 1 y omitía origen predeterminado; son rutas diferentes. |
| 52 | Servicios contratados BANDEC | Agregar `1*<tipo>*<factura>`; consultar `3*<tipo>*000`; eliminar `2*<tipo>*<factura>`; pagar `4*<tipo>*000*0*<origen>` | Tipo1 telecomunicaciones,2 electricidad. Kgii4NH4Ce abre q9DNC6bnexm para gestiones y g7yG4Frdxy para pago. |
| 55 | Amortizar crédito | `<cuenta>*<mensualidad>*<moneda>` · `<cuenta>*<monto>*<mensualidad>*<nombre>` | |
| 64 | Giro postal | `<ciOrigen>*<ciDestino>*<monto>*<moneda>*<idTipoGiro>*<movilDestinatario>[*<cuentaOrigen>]` | |
| 65 | Consultar giro | `<ci>` | `XvOK7VM4qL`, `gsthr0APCT`, `QGOIyTuo2a`, `jyBbMyVMOV`: formulario de giros pendientes. |
| 60 | Asociar cuenta | `<cuenta>` | |
| 69 | Cambiar clave | formulario propio | |
| 74 | Reimpresión de tarjeta | BPA `<moneda>*<origen>`; BANDEC `<sucursal>*<cuenta>*<motivo>`; BANMET `<moneda>*<origen>*<cuentaComision>*<municipio2>*<sucursal>` | kWnV7nzNWl:53, vVF87n7MnK:168, tYCC0hiRcm:97–100. |
| 79 | PIN digital | BPA `<moneda>*<origen>`; BANDEC `<tarjeta>*<coord1>*<valor1>*<coord2>*<valor2>`; BANMET `<tarjeta>*<coord1>*<valor1>` | Coordenadas A1–J10 y valores de 2 dígitos; datos sensibles. |
| 81 | Depósito a plazo fijo | `<importe>*<plazo>*<interés o modalidad>*<moneda>[*<cuentaOrigen>]` | |
| 89 | Estado de un pago | `<referencia>*<cuentaOrigen>` | |
| 24 | Obtener cuenta estándar | `<cuentaOrigen>` | `MAvaCEUqwa:47`, también invocado desde BPA; no es consulta de crédito. |
| 72 / 78 | Consultar crédito BPA / información de tarjeta Red | — | Rutas directas `kmO9Md6JHr:388-405` y `:291-308`, respectivamente. |
| 26 | Extracto por correo | `<correo>*<fechaInicio>*<fechaFin>*<cuentaOrigen>` | |
| 30 | QR dinámico | `<agencia>*<clave>*<id>*<importe>*<idMoneda>*<monedaTarjeta>*<proveedor>*<aux>*<origen>[*<movil>]` | Aux que contiene `11200301` se sustituye por 0; móvil vacío especial `0000`, ordinario `0` |
| 31 | QR estático | `<agencia>*<clave>*<id>*<importe>*<idMoneda>*<monedaTarjeta>*<proveedor>*<aux>*<descripcion>*<origen>[*<movil>*<idQR>]` | Descripción vacía e idQR omitido: `0000`. La cola opcional se activa por móvil indicado o idQR del extra |

Servicios Cubacel, no bancarios, se marcan tal cual y sin codificar: `*222#` saldo, `*222*266#` bonos, `*222*328#` datos, `*222*869#` voz, `*222*767#` SMS, `*222*264#` Plan Amigos, `*234*3#` adelanto de saldo, `*133#` compra de planes.

### 3.1 Variantes adicionales contrastadas en el APK (2026-09-23)

- Agua: servicios 19 (La Habana), 51 (resto del país), 21 (Varadero). BANDEC usa cinco campos en región 2 y seis en las otras, con moneda 0; BPA/BANMET siempre seis. La opción total es 1 en La Habana/Varadero; la pantalla inicia tipo 0 en resto del país. Fuentes: `zOs55UzH6z`, `T4RQ5wDJDt`, `BoMBck6OXT`.
- ONAT actual (43): `<modo>*<DPA>*<RC05/NIT>*<RC04/tributo>*<periodo>*<importe>*<moneda>*<origen>`; modo RC=1 usa DPA/período `0000`, NIT=2 requiere municipio y mes/año (`ROgpLrCtHa:391-435`). Sellos añade entidad antes de moneda. `TGMSi5UDp7:261,267` cambia el orden NIT/tributo en su ruta parcial; el DEX `submitForm` 010a/0110/0116 confirma el intercambio. El encoder conserva una variante parcial con ese orden y la misma semilla, antes de agrupar los paquetes.
- Nauta 59, Nauta Hogar 84 y deuda 86: `<usuarioSinDominio>*<tipoCuenta>*<importe>*<moneda>*<movil>*<origen>*<1/2/3>`. El móvil omitido es `0000000`. Propia 77, Joven Club 93, TFA 108, planes 6 y combustible 35 tienen campos específicos, recogidos en `ServiceOperations` con referencias al formulario.
- MiTransfer usa bolsa USD=1/CUP=2, distintas de las monedas bancarias. Recarga móvil vigente:54 CUP. Nauta Plus34 y Extra USD33 conservan las rutas de MiTransfer personal. Fuentes: `zzyNbaqs1U`, `HZgbT8IXPf`, `f3Ttlx30sMT`.
- Clásica 45 desde el menú usa `1*<tarjetaOrigen>*<destino>*<importe>*<compartirMovil>*<movilConfirmacion>*<compartirSaldo>*1`, bajo sesión MiTransfer. El prefijo `08*<PIN>*` solo aparece en la variante externa con argumento `R1sDOSqZ2z` (`l3GXbXAyUW:195-199,295-318`; entradas `OJ3XxacXOT`, `f6Y2Wmtg6jQ`, `KeTIEVSfVo`). El catálogo normal usa la primera ruta.
- Extracción MiTransfer95 concatena DPA con bolsa0 en el último átomo, sin separador (`f9Reflqt3EZ.submitForm`, DEX00e9–00ee). Se conserva el wire observado, sin afirmar aceptación de red.
- Consultar un extracto por correo (26) cobra comisión; no clasificarlo como lectura sin autorización. Los recibos de solicitudes no confirman un movimiento de dinero.
- Opciones contrastadas con los assets del mismo APK: `compraplanescubacel.json` fija categorías 1–7 y versión 2; `obligacionesOFA.json` conserva códigos de dos dígitos; `tipoPrelacion.json` define tres órdenes de pago; `entidadesEfectivo.json` diferencia CADECA CUP 22001 de USD 22002. `ProviderOptions` usa esos identificadores, sin pedir al usuario versiones del protocolo.

Los constructores son propios y no ejecutan USSD. Los fixtures de contratos y el oráculo de codificación comprueban orden, valores omitidos y sobre; no prueban aceptación por la red.

### 3.2 Navegación vigente y credenciales

Las rutas bancarias parten de `S9ycHcoxAzy`: BPA → `kChOHNVmzz` (consultas `kmO9Md6JHr`, operaciones `voNSkP3P8q`, configuración `f5AFyaj0rtx`); BANDEC → `s684VE6Dm0` (`R7PmVxXLJZC`, `bLlRBTTLue`, servicios contratados `Kgii4NH4Ce`, configuración `X7obECfkKf`); BANMET → `Q7htFIAC0SE` (`PEi17BvMam`, `Vdv5nYP39d`, `RC71RyOpsk`). Los constructores del catálogo citan el formulario que selecciona el handler de estas pestañas.

La presencia de una rama `case` no basta: también se contrasta el `initData` o una entrada real. No se publican pagos sindicales122, FWA18/20, recarga monedero23 ni bancaria88 sin una ruta activa demostrada. Los códigos antiguos no se sustituyen por otros para simular cobertura.

MiTransfer → `ofOuyCSKWI` usa sesión `H0kVHR7zuu`, consultas `UMVR3qrc3V`, operaciones `rR6oImkdEU` y configuración `hOQ4ESKEhm`. Clásica personal es una tarjeta asociada al acceso MiTransfer, con su misma autenticación. Asociación111 solicita tarjeta y códigos Aut/TML del recibo POS; desasociación68 usa el PIN del monedero. `authenticationIdentity` conserva ese registro padre, su línea y su SIM.




### 3.3 Moneda y valores derivados por contrato

`request.currency` no tiene una semántica universal. En transferencia bancaria 45 representa moneda del importe; en pagos de `ServiceOperations` representa débito para origen predeterminado BPA/BANMET, mientras BANDEC o un origen explícito envían 0. Los campos `amountCurrency` pertenecen al importe de la operación y no deben usarse para cambiar el producto de origen. MiTransfer usa `request.currency` para elegir bolsa USD1/CUP2; Clásica usa su instrumento explícito. Los filtros de productos deben respetar estas diferencias.

- Caja Extra32: `UTk9UWZYxf:316–323` acepta únicamente QR estático. `CashExtraOperations.fromQr/validateOriginal` conserva el QR de origen y rechaza cambios de comercio, referencia, moneda, importe fijo o descripción fija. Móvil omitido `00`; descripción omitida `0000`. El motor debe conservar ese original desde lectura hasta envío.
- BANMET asociación60 (tarjeta y matriz) es distinta de actualización53 (CI y Telebanca10 iniciada95). `banmet.associate-account` y `banmet.update-account` reflejan esas rutas; consultar créditos72 y localizar transferencias73 son directos. BANDEC información de tarjeta78 envía la tarjeta, a diferencia de la variante directa de BPA/BANMET.
- Identificadores de los pickers: los trámites BANMET74/76 envían municipio de dos dígitos (`kD9wg15jwt:86–88`, `tYCC0hiRcm:97–100`); apertura fiscal5 envía DPA completo de cuatro (`EyKrF4UJ9B:158–164`). La validación comprueba sucursal/municipio/banco, sucursal/servicio MiTurno, artículo/inciso de tránsito y entidades de sellos. Importe manual de sellos sigue permitido por la opción original.
- Los mensajes que contienen códigos o claves permanecen clasificados como sensibles, incluso cuando pertenecen a módulos retirados; la conservación histórica no habilita su ejecución.

### 3.4 Exclusiones por ruta activa y Nauta Plus

La conversión CUC→CUP87 no forma parte del catálogo normal: sus enums y handlers permanecen en el APK, pero `f5AFyaj0rtx.initData`, `X7obECfkKf.initData` y `RC71RyOpsk.initData` no añaden ese ítem. El registro de accesos directos `nljtDl5MIf` tampoco lo registra. La reflexión de `FBsQzP3Zfj.goToFragment` parte precisamente de ese registro. Se retiran las tres specs y sus fixtures; esto no afirma que el servidor haya eliminado87.

OFA94 se conserva únicamente para BANDEC, BANMET y MiTransfer: menús, enums y accesos directos demuestran esas rutas. BPA no tiene formulario94 ni subopción OFA: su diálogo ONAT ofrece RC, NIT y sellos43. No se adapta el contrato BANDEC a BPA por semejanza.

Nauta Plus34 tiene dos rutas activas en `rR6oImkdEU.showDialogNautaPlus` y el acceso directo equivalente de `FBsQzP3Zfj`: con cuenta `usuario*móvil*1`; sin cuenta `0000*móvil*1`. El móvil es obligatorio, de ocho dígitos y comienza por5/6. La cuenta se escribe sin espacios ni dominio y se conserva su capitalización; `toLowerCase()` no reasigna el campo. El layout no impone límite20 a la cuenta. La [FAQ oficial de ETECSA, pregunta43](https://www.etecsa.cu/es/preguntas-frecuentes?faq=4339) confirma la cuenta internacional y el móvil de notificación. El envío usa la ruta nativa `ACTION_CALL` de `FBsQzP3Zfj:144–171`, representada como `INTERACTIVE_USSD`; no se afirma haber inspeccionado la respuesta o aceptación de red.
### 3.5 MiTurno: solicitud y gestión posterior

- El envío102 solicita un turno: proveedor `1` BPA, `2` BANDEC, `3` BANMET o `4` MiTransfer, servicio, DPA de sucursal, CI/pasaporte, móvil de confirmación y débito según el contrato. No envía fecha ni hora.
- Cambiar fecha105 envía proveedor, servicio, CI/pasaporte y la fecha nueva elegida. Cancelar106 envía proveedor, servicio y CI/pasaporte. Consultar107 envía proveedor y CI/pasaporte. No se requiere ni se transmite un identificador de reserva: `rGY5dubJYU:211`, `GPsckxvkPu:204`, `V0zQxQBN2i:136`, `Wwc5crW71D:163`, `QVPgkl8ZGC:156`, `x12rSjPY6pZ:100`.
- El tipo de servicio viene del selector, no de un número escrito:1 compra de divisas CADECA,2 trámite comercial de gas,3 venta de gas. BPA/BANDEC publican esos tres. BANMET/MiTransfer añaden los once servicios bancarios4–14, desde `array_servicios_bancarios_miturno`; los handlers calculan posición+4. Solicitar exige además que la sucursal ofrezca ese servicio. La nueva fecha se elige entre hoy y seis meses después.
- `g0xM9kQCsqu:727–741` sólo demuestra una respuesta de solicitud completada con `Carnet de Identidad`, `Comision Pagada` y `Nro. Transaccion de Banco`. `MiTurnoReceiptParser` modela esos campos como recibo de solicitud/comisión. No se encontró una plantilla demostrada de105/106/107 con fecha, hora, número o estado del turno; esos datos no se inventan.
- Para gestión posterior se reutiliza la solicitud persistida en el diario por ID explícito, preservando proveedor, registro, SIM, beneficiario y servicio. La app debe validar procedencia y estado del registro: una operación preparada, cancelada antes de enviarse, rechazada o restaurada no prueba envío local. Incluso un recibo de comisión no prueba una cita asignada. `MiTurnoContracts.selectionFromRequest/prefill` prepara formularios desde los datos validados y deja vacía la nueva fecha hasta que la persona la elija.
## 4. QR y enlaces

- **Pago, JSON**: `{"id_transaccion":"…","importe":123.45,"moneda":"CUP","numero_proveedor":"…","version":1}`, opcional `descripcion`. El generador público usa comillas simples; `org.json` las acepta.
- `version` no vacía sustituye `aux` (predeterminado 1); no es la versión del sobre USSD. Importe 0 → servicio 31 con importe editable. Importe positivo e ID que contiene `ESTATICO` → 31 con importe fijo; los demás → 30. Descripción JSON no vacía es de solo lectura.
- JSON **sin `extra` es válido**. Si existe, `extra` es Base64 (admite espacios ASCII y saltos de línea), cifrado AES/CBC/PKCS5. Clave: primeros 16 caracteres hexadecimales minúsculos del SHA-256 del proveedor US-ASCII, convertidos a UTF-8. IV: mismo proceso sobre ID. Contenido: `descripcionHint,regex,fechas,idQR`. Fechas `ddMMyyyyddMMyyyy` inclusivas; `00000000` omite ese extremo. La regex se lee pero la ruta original no la aplica. Extra inválido se rechaza; no se ignora.
- Referencia bancaria moderna: `VW6IL66KC6M/r7Rinp9ooL` y `LgiFGRpiEl`, con su padre `Agg55uYctq`.
- MiTransfer QR usa agencia 04 y bolsa USD=1/CUP=2. Clásica usa bolsa 3 y añade la tarjeta tras la cola QR: en dinámico sin extras intercala un `0` antes de la tarjeta, mientras en estático la añade directamente. La variante especial `11200301` del original no añade tarjeta: su spec usa `SourcePolicy.NONE` y rechaza un origen explícito; no lo cambia silenciosamente.
- **Pago, texto plano**: 5 campos separados por coma que contienen `TRANSFERMOVIL_ETECSA` y `PAGO_EXTERNO`: `<idTransaccion>,<importe>,<moneda>,<numeroProveedor>,<aux>` **[estructura exacta no verificada]**.
- **Tarjeta, para transferir**: `TRANSFERMOVIL_ETECSA,TRANSFERENCIA,<tarjeta>,<movil>,` (coma final). Al escanear: campo 2 = tarjeta destino, campo 3 = móvil a confirmar.
- El original elimina los dos primeros dígitos de móviles de longitud 10. NeoTransfer solo normaliza ese caso cuando comienza por `53`; otros prefijos se rechazan.
- **Deep link**: `transfermovil://tm_compra_en_linea/action?id_transaccion=&importe=&moneda=&numero_proveedor=[&descripcion=]`; App Link `https://transfermovil.app/action?…` con los mismos parámetros. Las tiendas web lo abren para pagar.
- Lectura: cualquier decodificador QR. La app original usa ML Kit para leer y ZXing para generar.

## 5. SMS de resultado

En la línea 1.0, el transporte y el diario conservan por separado recepción y marca temporal SMSC. A los 30 segundos se muestra «Tiempo de espera agotado» y se libera la espera; una respuesta posterior se procesa sin reactivar pagos ni exigir revisión. La interfaz habitual no expone los SMS originales. Los comportamientos de 0.2 descritos en los cotejos históricos no sustituyen esta política; véase [arquitectura](arquitectura.md).

- Remitente `PAGOxMOVIL`. La app lo recibe por `SMS_RECEIVED` y además lee la bandeja (`content://sms/inbox` con `address = 'PAGOxMOVIL'`) para reconstruir el historial: nuestro historial puede nacer de la bandeja el primer día.
- Texto libre en español sin tildes. La app extrae campos por marcadores `Etiqueta:` (`getElement("Id Transaccion:", sms)`); tras el importe viene la moneda en 3 letras.
- Marcadores de éxito conocidos: "La Transferencia fue completada.", "El pago de la factura de electricidad fue completado", "El pago de la factura telefonica fue completado", "El pago de la factura del agua fue completado.", "El pago de la factura del Gas fue completado.", "El pago de la ONAT fue completado", "El pago de la multa fue completado.", "La recarga se realizo con exito", "Pago completado", "La compra fue completada.", "Giro Postal fue completado.", "Bienvenido al sistema TRANSFERMOVIL".
- Campos conocidos: "Id Transaccion:", "Nro. Transaccion:", "Monto Pagado:", "Importe Factura:", "Id Compra:", "Nro. Factura Pagada:", "NIT:".
- Registro de comprobante en la app original (RecordSMS): idTransaccion, fecha, cuenta, monto, moneda, servicio, tipo_servicio (BancoBPA / BancoBANDEC / BancoMetro).
- Saldo, autenticación y comprobantes BANDEC/BPA observados por ADB (§5.1–5.2). No inferir formatos de otros bancos.

### 5.1 Muestra real de BANDEC (2026-09-22)

Lectura de los 12 SMS más recientes de `PAGOxMOVIL` mediante `adb shell content query`, filtrando `content://sms/inbox` por remitente y ordenando por fecha descendente. No se enviaron solicitudes bancarias. Se observaron 2 transferencias enviadas, 2 recibidas, 2 consultas de saldo, 4 autenticaciones y 2 rechazos de recarga. Los siguientes esquemas sustituyen los datos personales por marcadores; no son fixtures literales.

- **Transferencia enviada:** `Banco Bandec: La Transferencia fue completada.`, seguido por `Fecha:`, `Beneficiario:`, `Ordenante: CUP`, `Monto: <importe> CUP`, `Nro. Transaccion: <referencia>` y `Saldo restante: CR <importe> CUP`. Beneficiario llega como tarjeta parcialmente enmascarada. En esta muestra `Ordenante` contiene la moneda, no una tarjeta: no usarlo para identificar la cuenta de origen.
- **Saldo:** `Banco Bandec La consulta de saldo fue completada.`, cabecera `Cuenta;Saldo Contable;Saldo Disponible;Moneda` y fila `<tarjeta enmascarada>; CR <importe> ; CR <importe> ;CUP |`. Conservar ambos saldos separados. Las muestras solo contienen una cuenta y marcador `CR`; otros marcadores y respuestas multicuenta siguen sin comprobar.
- **Transferencia recibida:** `El titular del telefono <movil> le ha realizado una transferencia a la cuenta <cuenta> de <importe> CUP. Nro. Transaccion <referencia>. Fecha: <fecha>.` No incluye saldo restante; `Nro. Transaccion` aparece sin dos puntos.
- **Autenticación:** `Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta <tarjeta enmascarada>, puede comenzar a utilizar nuestros servicios de pagos a traves del movil`.
- **Rechazo de recarga:** `Fallo la recarga del movil <movil> alcanzo el monto limite de recarga permitido en 30 dias (<limite> CUP), puede recargar posterior al dia <fecha>.` Este rechazo no demuestra un error de sesión.

La muestra confirma saldo restante en transferencias **enviadas de BANDEC**, no en todos los pagos ni bancos. No contiene pagos QR. Las líneas en blanco y los espacios varían; las referencias de transacción son alfanuméricas.

Una ampliación posterior a 200 mensajes encontró cinco pagos/compras (`Pago completado`, `Fecha`, `Id Compra`, `Importe pagado`) y dos recargas exitosas (`Monto Pagado`). No demuestra que esos pagos fueran QR ni que `Id Compra` sea el ID del QR. Por eso NeoTransfer no confirma automáticamente un QR solo por importe o por proximidad temporal: conserva el pendiente y permite revisar el SMS. Cerrar manualmente una transferencia incierta conserva su huella de correlación para no atribuir un comprobante tardío a otra operación compatible.

Regla de producto ya acordada: tras mover dinero, aprovechar el saldo del SMS de confirmación; si falta o pasan 10 s sin confirmación, consultar saldo automáticamente cuando el canal USSD esté libre. Consultar saldo no equivale a confirmar la operación pendiente ni autoriza a repetirla; admitir una confirmación tardía sin duplicar el movimiento. Un SMS de autenticación tampoco confirma una transferencia.

### 5.2 Variantes BPA y recibos de servicios (2026-09-22)

Lectura de 225 SMS existentes, sin solicitudes bancarias. Fixtures de pruebas sintéticos; los cuerpos personales no se guardan en el proyecto.

- BPA autentica sin número de cuenta; puede añadir un bloque `Informacion:`. BANDEC también puede omitir el valor tras `con la cuenta`. La ausencia se conserva como `null`.
- BPA responde con `Saldo Disponible: CR <importe> <moneda>` o tabla `Nombre Cuenta;Nro Cuenta;Saldo Disponible;Moneda`. En las tablas observadas, `Nro Cuenta` contiene un rótulo, no una tarjeta. No inventar cuenta ni saldo contable. `DB` no está demostrado como saldo disponible y continúa sin interpretarse.
- Hay transferencias recibidas sin el sufijo de fecha. La frase impersonal `Se ha realizado una transferencia a la cuenta...` no demuestra dirección ni propiedad; cinco avisos repiten comprobantes enviados existentes y dos quedan ambiguos.
- Nauta Hogar y recarga Nauta separan importe del servicio e importe realmente pagado. Si falta `Monto Pagado`, el historial muestra el nominal identificado como tal, sin signo de débito ni descuento calculado. Sello del timbre separa `Valor del sello`, `Importe Pagado` y `Nro. Transaccion Banco`.
- Movimientos incluye únicamente comprobantes tipados. Autenticaciones, saldos, fallos, resúmenes y avisos ambiguos permanecen en Mensajes. Duplicados con la misma proyección financiera, referencia y SIM dentro de cinco minutos se muestran una sola vez; ambos SMS siguen disponibles.
- `Ya se encuentra autenticado` conduce a una consulta de saldo de solo lectura. Continuar exige acuse y SMS reciente del banco/SIM esperados. Fechas futuras o evidencia contemporánea de otro banco invalidan la prueba; ningún fallo reenvía dinero.

### 5.3 Contraste ampliado de historial (2026-09-24)

Lectura autorizada de 229 SMS existentes de `PAGOxMOVIL`, sin ejecutar solicitudes bancarias. Cuatro respuestas BANDEC de últimas operaciones contienen 33 filas en total y confirman la cabecera y las columnas que reconoce `BankHistory.kt:17-41`. La muestra no incluye respuestas de historial BPA/BANMET 48, últimos pagos BANDEC 63 ni MiTurno 105/106/107; sus formatos siguen sin demostrar. Los cuerpos, referencias, fechas, cuentas e identidades de esta lectura permanecen fuera del proyecto.

El parser actual dejó 38 mensajes sin tipar. Una revisión agregada encontró 18 con indicios de rechazo/error, 12 sin marcador de resultado y ocho con lenguaje de operación completada. De estos últimos, siete son avisos impersonales de transferencia ya descritos en §5.2: cinco comparten referencia con comprobantes tipados y dos carecen de dirección demostrada. El octavo contiene datos de autenticación de una gestión bancaria, pero no es prueba de sesión autenticada según `SemanticAuthentication`; debe conservarse como mensaje sensible sin promoverlo a éxito de acceso. Entre los doce sin marcador hay avisos de pago o recarga con cuenta y transacción, pero la muestra no acredita banco, titularidad ni efecto financiero para proyectarlos como movimientos. El SMS original se conserva sin reinterpretar.

## 6. Requisitos Android

Cliente: **NeoTransfer**, paquete `dev.duardo.neotransfer`, Android nativo con Kotlin y Jetpack Compose. Mínimo decidido: **Android 15 (`minSdk 35`)**. El teléfono conectado el 2026-09-22 reporta Android 16 mediante `adb shell getprop ro.build.version.release`; esto comprueba la versión, no el funcionamiento de USSD o biometría de nuestra app.

Permisos: `CALL_PHONE`, `READ_PHONE_STATE`, `RECEIVE_SMS`, `READ_SMS`, `CAMERA`. El selector de contactos usa `ACTION_PICK` y el acceso temporal a la fila elegida, sin solicitar lectura de toda la agenda. `sendUssdRequest` exige API 26; Keystore con autorización biométrica fuerte por uso exige API 30. Instalación fuera de Google Play, que restringe los permisos de SMS.

La versión 0.2.0 guarda el PIN con AES-GCM en Android Keystore y pide biometría al abrir y al autorizar dinero. Las capturas están permitidas en saldos, movimientos y comprobantes; se protegen acceso/PIN, biometría y mensajes con credenciales, además de la miniatura de aplicaciones recientes. Los permisos necesarios se solicitan automáticamente en el inicio inicial; Android mantiene sus diálogos de consentimiento. La sesión bancaria local se invalida al pasar a segundo plano y ante evidencia nueva de otro banco en la misma SIM. El diario pendiente y la consulta de saldo se guardan antes del envío; una consulta de saldo nunca confirma una transferencia.

Validación 0.2.0: 42 pruebas JVM (incluidos 1.400 vectores del codificador y 800 QR) y 30 casos ejecutados en Android con módem simulado sobre el APK release firmado. Cubren sesión ya autenticada, SIM/banco incorrectos, pérdida del broadcast, fechas futuras, reinicio, SMS adelantado/tardío/duplicado, valores ausentes, historial y lectura de QR. UI recorrida en el teléfono en ambos temas y con letra al 150 %. **No demuestran aceptación de los códigos por el banco ni autenticación biométrica física.** Esas pruebas requieren al titular en su teléfono; no se automatizan transferencias reales.

Prueba real posterior: el titular abrió 0.2.0 con su huella, pulsó Consultar y confirmó «Actualizó el saldo». ADB comprobó un SMS de saldo reciente y la ventana normal sin `FLAG_SECURE`. Esta evidencia acredita la consulta probada, no el resto de operaciones monetarias.

Build: `gradlew.bat :core:test :app:assembleRelease :app:lintRelease` con Java 21 y SDK 36. La firma privada está fuera del proyecto, en el perfil local `.neotransfer/signing.properties` y `release-signing.p12`; conservar ambos para futuras actualizaciones. El APK de revisión `preview` tiene otro paquete, datos ficticios y ningún permiso de llamadas o SMS; no se distribuye como app bancaria.

### Cobertura de NeoTransfer 0.2.0

El catálogo del apartado 3 describe el protocolo, no funciones terminadas. La app expone autenticación, saldo predeterminado, cierre de sesión bancaria, transferencia, recarga móvil, electricidad/teléfono, QR y apertura de enlaces de pago. La aceptación bancaria de cada operación necesita prueba real; no se deduce del build. HTTPS no se declara App Link verificado para un dominio ajeno.

Siguen fuera de la interfaz: gestión completa de tarjetas y destinatarios, historial solicitado al banco (48/63), límites (61/62), consulta de estado del pago (89), QR de cobro propio, agua, gas, ONAT, créditos, giros y trámites de registro/tarjeta. Los contratos marcados sin verificar deben resolverse antes de habilitarlos. El historial SMS de servicios no significa que ya exista su formulario de pago.

## 7. Catálogos y gestiones personales

### Catálogos y gestiones activas completadas en core

- `TerritoryCatalog` conserva16 provincias y168 municipios del catálogo DPA usado por los servicios personales. `ServiceCatalogData` conserva además sucursales, entidades y relaciones territoriales necesarias para los selectores.
- BANDEC tiene cinco pestañas: `s684VE6Dm0.getItem(3) → Kgii4NH4Ce` expone servicios contratados; configuración está en `X7obECfkKf`. Agregar/consultar/eliminar abren `q9DNC6bnexm` y servicio **52** con tres parámetros: operación `1/3/2`, tipo `1` teléfono o `2` electricidad, factura o `000` al consultar. Pagar abre `g7yG4Frdxy`, operación `4`, y añade moneda `0` y origen. No anexar origen a las otras tres variantes.
- Los tres bancos BPA/BANDEC/BANMET publican `showConsultaCombustible`: **36** operaciones por serie, **37** cupones por fecha `dd/MM/aaaa`, **38** estado por serie, **39** actualizar clave por serie. `UceJvUZMUO` exige serie de 13 dígitos. La operación 39 es de seguridad.
- Consulta de multas **98**, ruta `showDialogMultasConsulta → ULFE6hmQU6`: tipo `1/2`, multa, CI o `0000`, licencia o `0000`. Su emisor parcial declara agencia `02` literalmente para todos los bancos; se conserva en el contrato.

## Apéndice A: constantes de codificación (APK 1.260416)

```
code_version = "372653282298718711"
crude_code = "10000110101100011000100100100000110010001100000011100010100110010001010010100010111110001100010000011000010101100000000110001100101010100100011000000101110001100001100010001011110001100010100001000110000001101000101001001101001000001000010001100100011010010111001000110011110010001001100001000100000010000111001000110000101110100001111110011000001000110010000111110001100001111111000110011000101001100101000110001000111100011000101011101011111100111010100010111101000110000110011000110011100011000100111000101000011100010100111000011101100011000010101010000010101000100010100011001010101101000110001011101100011001001101101001111100011000000100010001100100010110101000110110000110000101000100011000000010100000010010001101011011100010110001110001001011100000110110001100001000011001011010100101100010001100100011110100011000011000110001100101010110101011110001100010000101000110001001101010010001001110001000010110110001100100111010001101101000110010010110001000110101000011110110000011101000110001001001100100010010000001111000110010011101110001100000001011000110000100011001111101000110000010010100011000110111100101100101011001100000010011000110000000001000100111100011000101101000100100000100010010101000100011100011000001101110001110011000101001"
```

## Apéndice B: diccionario de texto (carácter → 2 dígitos), clase EKB7Plln4L

| Carácter | Código |
|---|---|
| `.` | 15 |
| `-` | 85 |
| `_` | 58 |
| `0` | 37 |
| `/` | 11 |
| `,` | 29 |
| `%` | 67 |
| `&` | 20 |
| `a` | 59 |
| `b` | 00 |
| `c` | 82 |
| `1` | 89 |
| `A` | 05 |
| `B` | 54 |
| `C` | 60 |
| `á` | 88 |
| `@` | 64 |
| `d` | 51 |
| `e` | 93 |
| `h` | 50 |
| `2` | 41 |
| `D` | 57 |
| `E` | 65 |
| `F` | 61 |
| `é` | 91 |
| `#` | 08 |
| `g` | 30 |
| `f` | 46 |
| `i` | 97 |
| `3` | 80 |
| `G` | 83 |
| `H` | 13 |
| `I` | 55 |
| `í` | 53 |
| `;` | 56 |
| `j` | 76 |
| `k` | 25 |
| `l` | 42 |
| `4` | 66 |
| `J` | 73 |
| `K` | 17 |
| `L` | 38 |
| `ó` | 32 |
| `m` | 27 |
| `n` | 92 |
| `o` | 12 |
| `ñ` | 03 |
| `5` | 01 |
| `M` | 06 |
| `N` | 49 |
| `O` | 18 |
| `Ñ` | 33 |
| `p` | 98 |
| `q` | 23 |
| `r` | 31 |
| `6` | 94 |
| `P` | 44 |
| `Q` | 35 |
| `R` | 34 |
| `ú` | 87 |
| `s` | 19 |
| `t` | 45 |
| `u` | 81 |
| `7` | 62 |
| `S` | 22 |
| `T` | 40 |
| `U` | 10 |
| `v` | 96 |
| `w` | 69 |
| `x` | 75 |
| `8` | 24 |
| `V` | 14 |
| `W` | 70 |
| `X` | 74 |
| `y` | 09 |
| `z` | 90 |
| ` ` | 78 |
| `9` | 72 |
| `Y` | 71 |
| `Z` | 07 |
| `Á` | 99 |
| `É` | 86 |
| `Í` | 47 |
| `Ó` | 36 |
| `Ú` | 39 |
| `ü` | 77 |
| `Ü` | 95 |
