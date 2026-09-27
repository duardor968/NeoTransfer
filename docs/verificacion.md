# Verificación del desarrollo 1.0

Corte: 27 de septiembre de 2026, versión de desarrollo `1.0.0-dev.3`. Estos resultados no son una certificación de operaciones bancarias ni una release 1.0.0.

## Comprobado

- **193 pruebas JVM**, sin fallos: contratos, validaciones, análisis de recibos, respaldo, controladores y presentación de cuentas conocidas. Incluyen el rechazo de CUC para nuevas operaciones, conservación del código USD y lectura histórica de CUC.
- **241 comprobaciones Android**, sin fallos, en TECNO CM5 con Android 36, más una ejecución específica anterior de contenido de notificaciones. Usan bases aisladas, claves y mensajes sintéticos, y un transporte que no envía operaciones bancarias.
- Migración de preferencias de la base 0.2 y de esquemas Room 1/2/3 a 4, repetición y rollback; preservación de pendientes sin reenvío; restauración de cupones con otra clave local; rechazo de evidencia ambigua, futura o restaurada.
- Timeout de 30 segundos compartido entre autenticación, selección y envío; espera de 163 segundos, callbacks antiguos, petición nueva después del timeout, saldos recibidos en orden inverso y consultas tardías tras recrear el ejecutor. Esta última prueba usa el mismo Room; no simula muerte real del proceso Android.
- Saldo restante ligado al origen explícito y cronología SMSC; consulta puntual cuando falta, sin repetirla ni prolongar el plazo original. Cambio de tarjeta visible sin redirigir la operación, retirada de SIM, bloqueo y salida de la app. Estos casos se probaron con transporte simulado; no acreditan pagos reales.
- QR y OCR desde imágenes sintéticas mediante ML Kit instalado, varios números y una imagen inclinada. La prueba comprueba también la posición del marco de detección en un visor Android real.
- Publicación de una notificación sintética mediante NotificationManager: contacto e importe, versión pública genérica y un solo aviso ante repetición. El permiso temporal de la variante de desarrollo se restauró al finalizar.
- Compilación de las variantes debug, preview y androidTest, y lint. El icono de la ventana biométrica se comprobó visualmente en el teléfono por el titular; su regresión se prueba a varios tamaños.
- Recorridos con datos ficticios en claro, oscuro y letra ampliada: cartera, contactos, cambio de origen conservando destinatario, actividad, comprobante, ajustes, MiTurno hasta revisión, cupones y consultas SMS simuladas. Una revisión visual independiente no dejó defectos abiertos en esos recorridos.
- Revisión posterior del bloqueo siguiendo la referencia de Authenticator, acceso sin tarjeta completa y vencimiento: escritura `0236` → `02/36`, mes inválido impedido, corrección y guardado. Una confirmación tardía simulada conserva la vista de Contactos. Estas capturas proceden de Preview y no sustituyen la revisión del APK principal.
- Lectura local autorizada de 229 mensajes PAGOxMOVIL: cuatro respuestas reales de últimas operaciones BANDEC, con 33 filas reconocidas. Las fuentes privadas no forman parte del repositorio; [protocolo](protocolo.md) documenta el contrato sin sus datos.
- En `dev.2`, el titular configuró BANDEC, añadió una tarjeta y obtuvo saldo tras dos timeouts. Se comprobó que el importe de la cartera coincidía con la última respuesta de saldo. Una autenticación llegó con 101 segundos de demora y fuera de orden respecto del saldo; no se ejecutaron transferencias reales.
- Actualización de `dev.2` a `dev.3` sin borrar datos: el titular confirmó tras desbloquear que se conservaron la tarjeta y el saldo consultado.

## Pendiente para la entrega

- Consultas y selección de origen de esta versión con BPA/BANDEC; las operaciones monetarias reales requieren autorización del titular. BANMET, MiTransfer y Clásica conservan evidencia técnica separada de aceptación real.
- Respuestas demostradas de historial BPA/BANMET y últimos pagos BANDEC63; resultados de MiTurno105/106/107 y del historial de cupones. No extrapolar formatos ni inferir éxito.
- Cámara física con papel, manuscritos, reflejos y distintas condiciones; recepción y apertura de notificaciones tras muerte del proceso y restricciones del fabricante. La prueba de NotificationManager no reproduce esos escenarios ni el cierre forzado.
- Fidelidad 1:1 de las tarjetas: los recursos actuales son reconstrucciones y todavía no la acreditan. Revisión de los demás recorridos y publicación final con SHA-256. El APK de prueba `dev.3` está firmado con el mismo certificado de la instalación previa; no se ha publicado una release 1.0.0.

Los comandos de reproducción están en [desarrollo](desarrollo.md). GitHub Actions ejecuta pruebas JVM, compilación y lint; no ejecuta las comprobaciones del teléfono ni banca real.
