# Cobertura de la entrega experimental

Esta página describe `1.0.0-dev.6` (`versionCode 8`). **No es una release 1.0 ni certifica aceptación por ENZONA, bancos o proveedores.** Las APIs ENZONA están implementadas a partir de análisis estático; no se ha probado una cuenta real ni movimiento de dinero por ese canal. La preparación actual usa QA sintética; el titular hará la prueba real en su teléfono.

| Área | Presencia en el código de desarrollo | Límite conocido |
| --- | --- | --- |
| Tarjetas propias BPA y BANDEC | Cartera, consultas, transferencias, QR, actividad y comprobantes; se conserva USSD/SMS. | La consulta real previa de saldo y los SMS observados acreditan únicamente sus casos documentados. No certifican ENZONA ni todos los pagos. |
| Cuenta ENZONA | Sesión y consulta de cuenta y tarjetas, con persistencia separada. | Para esta prueba, registro de cuenta y primera vinculación de tarjetas en ENZONA oficial. Alta nativa de tarjetas aplazada; acceso real pendiente. |
| Saldo, transferencias y QR ENZONA | APIs de lectura, constructores de pago y diario de intentos implementados. | Contratos estáticos; aceptación, conciliación e historial real pendientes. El historial ENZONA y las últimas operaciones bancarias son fuentes distintas. |
| Electricidad y telefonía fija | Consulta y pago ENZONA, además del canal USSD conservado. Alta/listado de referencias remotas implementados. | Guardar una referencia local no equivale a registrarla en ENZONA. Un HTTP 200 no demuestra pago aceptado. |
| Nauta y Nauta Hogar | Consulta y recarga de cuentas ENZONA; datos de deuda separados en la respuesta. | Recargar no demuestra liquidar deuda. El pago separado de deuda Hogar continúa por USSD. |
| Agua y recarga móvil desde banco | Funciones conservadas por USSD. | No hay contrato de envío ENZONA demostrado para estos dos servicios. No se inventan endpoints ni se cambia de canal automáticamente. |
| Cubacel desde saldo telefónico | Planes, consultas de saldo/datos/minutos/SMS/bonos y transferencia de saldo como extra; menú `*133#`. | No equivalen a recarga bancaria. Una secuencia observada de menú no demuestra un contrato permanente de paquetes. |
| Notificaciones | Se conservan la recepción y el tratamiento de SMS existentes. | No hay push ENZONA demostrado con la app cerrada; consultar actividad no garantiza avisos en segundo plano. |
| Cámara | QR y lectura de números impresos mediante CameraX y ML Kit; existen casos instrumentados con imágenes sintéticas. | No se ha demostrado lectura de manuscritos, reflejos o documentos reales; las pruebas instrumentadas requieren ejecución en dispositivo. |
| Contactos y respaldo | Importación de contactos, datos locales, exportación y restauración cifradas. | La compatibilidad con respaldos y datos instalados requiere pruebas de migración y restauración antes de publicar. |

Fuera de esta entrega:

- Tarjetas propias BANMET, monederos MiTransfer CUP/USD y perfiles agente, Clásica personal y Empresarial, BFI y Bulevar. Los destinatarios de otros bancos no se excluyen por esta limitación de productos propios.
- MiTurno completo —CADECA, gas y citas bancarias—, cupones de combustible, MiBoleto, reservas OFA y Votomóvil.
- Gas, Propia, TFA, Joven Club, ONAT, cuentas fiscales, sellos, multas, giros postales, Caja Extra, créditos, depósitos a plazo y aportes PCC/UJC/UIC y Oficina del Historiador.
- Límites de tarjetas, generación/recuperación de PIN físico, reimpresión, apertura de cuentas MLC y estados de cuenta por correo.
- Adelanto de saldo Cubacel, recarga por código de cupón, atajos de llamadas normales/por cobrar/de emergencia e información comercial por SMS.

La conservación de contratos, identificadores o datos históricos de módulos excluidos no habilita sus formularios ni su ejecución. Contactos, cámara/OCR, referencias, biometría y respaldos permanecen en alcance. El desbloqueo local y las credenciales de cada proveedor mantienen identidades independientes.

Para evaluar una operación concreta se necesitan, por separado: contrato de entrada y salida, validación local, flujo de interfaz, ejecución en dispositivo, tratamiento de rechazo e incertidumbre y, cuando proceda, aceptación real del proveedor. Los resultados con su evidencia se mantienen en [verificación](verificacion.md); esta tabla no sustituye esas pruebas.
