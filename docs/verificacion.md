# Verificación del desarrollo 1.0

Corte: 24 de septiembre de 2026. Estos resultados corresponden al código en desarrollo; no son una certificación de operaciones bancarias ni una release 1.0.0.

## Comprobado

- **176 pruebas JVM**, sin fallos: contratos, validaciones, análisis de recibos, respaldo, controladores y presentación de cuentas conocidas.
- **143 comprobaciones Android**, sin fallos, en TECNO CM5 con Android 36. Usan bases aisladas, claves y mensajes sintéticos, y un transporte que no envía operaciones bancarias.
- Migración de preferencias de la base 0.2 y de esquemas Room 1/2 a 3, repetición y rollback; preservación de pendientes sin reenvío; restauración de cupones con otra clave local; rechazo de evidencia ambigua, futura o restaurada.
- QR y OCR desde imágenes sintéticas mediante ML Kit instalado, varios números y una imagen inclinada. La prueba comprueba también la posición del marco de detección en un visor Android real.
- Publicación de una notificación sintética mediante NotificationManager: contacto e importe, versión pública genérica y un solo aviso ante repetición. El permiso temporal de la variante de desarrollo se restauró al finalizar.
- Compilación de las variantes debug, preview y androidTest, y lint. El icono de la ventana biométrica se comprobó visualmente en el teléfono por el titular; su regresión se prueba a varios tamaños.
- Recorridos con datos ficticios en claro, oscuro y letra ampliada: cartera, contactos, cambio de origen conservando destinatario, actividad, comprobante, ajustes, MiTurno hasta revisión, cupones y consultas SMS simuladas. Una revisión visual independiente no dejó defectos abiertos en esos recorridos.
- Lectura local autorizada de 229 mensajes PAGOxMOVIL: cuatro respuestas reales de últimas operaciones BANDEC, con 33 filas reconocidas. Las fuentes privadas no forman parte del repositorio; [protocolo](protocolo.md) documenta el contrato sin sus datos.

## Pendiente para la entrega

- Actualizar la instalación principal 0.2 con la firma existente y comprobar sus datos y accesos con la biometría del titular. Las migraciones sintéticas no sustituyen esa prueba.
- Consultas y selección de origen de esta versión con BPA/BANDEC; las operaciones monetarias reales requieren autorización del titular. BANMET, MiTransfer y Clásica conservan evidencia técnica separada de aceptación real.
- Respuestas demostradas de historial BPA/BANMET y últimos pagos BANDEC63; resultados de MiTurno105/106/107 y del historial de cupones. No extrapolar formatos ni inferir éxito.
- Cámara física con papel, manuscritos, reflejos y distintas condiciones; recepción y apertura de notificaciones tras muerte del proceso y restricciones del fabricante. La prueba de NotificationManager no reproduce esos escenarios ni el cierre forzado.
- Cotejo final de las representaciones de tarjetas, revisión de los demás recorridos, APK de distribución firmado y publicación con SHA-256.

Los comandos de reproducción están en [desarrollo](desarrollo.md). GitHub Actions ejecuta pruebas JVM, compilación y lint; no ejecuta las comprobaciones del teléfono ni banca real.
