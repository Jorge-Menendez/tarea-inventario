# Trazabilidad del inventario

Se utiliza SLF4J 2.0.16 con Logback 1.5.16. Las versiones se fijan en propiedades Maven y se verificaron con los artefactos disponibles en la caché local. No se afirma que sean las últimas versiones; antes de producción deben revisarse y actualizarse dentro del proceso de mantenimiento de dependencias.

`Inventory.create` devuelve un `LoggingInventoryService` que decora el servicio de aplicación. El decorador registra el ciclo de cada llamada, crea su contexto y lo restaura al terminar. El servicio de aplicación registra las transiciones del negocio. El dominio mantiene sus reglas sin depender del logger. Si se construye directamente `DefaultInventoryService`, envolverlo en `LoggingInventoryService` para obtener el contexto y el seguimiento de inicio/fin.

## Uso

Ejecutar con el nivel predeterminado, INFO:

```bash
mvn test
```

Activar el detalle completo, incluidas consultas, políticas y duración del bloqueo:

```bash
INVENTORY_LOG_LEVEL=DEBUG mvn test
```

También se puede usar una propiedad de sistema:

```bash
mvn -DINVENTORY_LOG_LEVEL=DEBUG test
```

Los logs se escriben en consola. Para conservarlos durante una ejecución:

```bash
INVENTORY_LOG_LEVEL=DEBUG mvn test > inventory-debug.log 2>&1
```

Buscar todos los eventos de una traza, sustituyendo el identificador:

```bash
rg -F 'traceId=IDENTIFICADOR' inventory-debug.log
```

Buscar un pedido o los vencimientos:

```bash
rg -F 'orderId=ORDER-1' inventory-debug.log
rg -F 'event=reservation_expired' inventory-debug.log
```

Los logs generados están excluidos de Git. No se añade un appender a disco por defecto: el entorno que ejecute el servicio puede recoger la consola, definir retención o configurar otro appender de Logback.

## Campos comunes

Cada línea incluye fecha en UTC, nivel, hilo y logger, además de:

| Campo | Significado |
| --- | --- |
| `traceId` | Correlación con el llamador. Se conserva el que exista en MDC; si falta, se genera un UUID. |
| `operationId` | UUID nuevo para cada llamada, incluidos los reintentos. |
| `parentOperationId` | Operación del mismo hilo que provocó una llamada anidada, por ejemplo desde un listener. |
| `operation` | `registerProduct`, `addStock`, `reserve`, `confirm` o `available`. |
| `orderId` | Pedido solicitado, cuando se conoce al entrar. |
| `sku` | Producto solicitado, cuando se conoce al entrar. |
| `quantity` | Cantidad solicitada, cuando corresponde. |
| `event` | Nombre estable del evento. |

En `confirm` solo se recibe el pedido: SKU y cantidad se incluyen en el mensaje de confirmación cuando ya se resolvió la reserva. El contexto de entrada puede mostrar `sku=none`.

Un vencimiento puede procesarse como consecuencia de una operación sobre otro producto. El contexto común identifica la llamada que lo provocó; `expiredOrderId` y `expiredSku` identifican la reserva liberada. No interpretar el SKU del contexto como el producto vencido.

Los identificadores se sanitizan únicamente al registrarlos: se sustituyen caracteres de control y separadores de línea, y se truncan a 256 caracteres. Los datos originales del negocio no se modifican. La búsqueda de identificadores largos o con caracteres especiales debe utilizar su representación en el log. No se imprime el mapa MDC completo ni el estado completo del almacenamiento.

## Eventos y niveles

| Nivel | Eventos |
| --- | --- |
| INFO | Inicio y éxito de operaciones de escritura; registro de producto, reposición, reserva, confirmación, vencimiento y avisos. |
| DEBUG | Inicio y éxito de consultas; disponibilidad, resolución de políticas, descarte de entradas confirmadas, decisión de no avisar y tiempos del almacenamiento. |
| WARN | Rechazos esperados del negocio y fallo recuperable del listener. |
| ERROR | Fallos inesperados de ejecución o errores, con su excepción; se vuelven a propagar. |

Eventos de operación:

- `operation_started`.
- `operation_completed`, con `outcome=success` y `durationMicros`.
- `operation_rejected`, con tipo, motivo sanitizado y duración. No incluye stack trace para errores esperados.
- `operation_failed`, con tipo, duración y stack trace.

Eventos de negocio:

- `product_registered` y `product_registration_replayed`.
- `stock_replenished`, con cantidad y disponibilidad antes/después.
- `reservation_created`, con vencimiento y disponibilidad antes/después.
- `reservation_replayed`, con estado y vencimiento originales.
- `reservation_confirmed` y `confirmation_replayed`.
- `reservation_expired`, con fecha de vencimiento, fecha de procesamiento y stock antes/después.
- `expiration_entry_discarded`, para una entrada que ya estaba confirmada.
- `availability_read`, con disponibilidad y existencia del producto.
- `reservation_policy_resolved`, con categoría, duración y límite.
- `low_stock_alert_claimed` y `low_stock_alert_not_required`.
- `notification_started`, `notification_completed` y `notification_failed`.
- `store_operation_finished`, con `waitMicros` y `exclusiveMicros`.

`ReservationUnavailableException` es una subclase interna de `IllegalStateException`: identifica un rechazo esperado al confirmar un pedido ausente o vencido. Una `IllegalStateException` por inconsistencia del almacenamiento se registra como ERROR. El contrato público conserva su tipo de excepción esperado.

Las duraciones se calculan con `System.nanoTime`, independientemente del reloj de negocio inyectado. La duración total incluye espera del bloqueo y ejecución del listener. `exclusiveMicros` mide la ejecución bajo el bloqueo; su evento se escribe después de liberarlo.

## Ejemplo de recorrido

Los identificadores y tiempos siguientes son ilustrativos:

```text
INFO traceId=T operationId=A operation=reserve orderId=ORDER-1 sku=SKU-1 event=operation_started
INFO traceId=T operationId=A operation=reserve orderId=ORDER-1 sku=SKU-1 event=reservation_created quantity=3 availableBefore=10 availableAfter=7
INFO traceId=T operationId=A operation=reserve orderId=ORDER-1 sku=SKU-1 event=operation_completed outcome=success durationMicros=250
```

Un reintento usa otro `operationId` y genera `reservation_replayed`, no `reservation_created`. `traceId` solo se comparte entre llamadas independientes si el llamador lo proporciona mediante MDC; se puede seguir el pedido a través de distintas trazas utilizando `orderId`.

## Contexto, fallos y límites

El MDC anterior se restaura en `finally`, incluso ante excepciones y callbacks anidados. Los campos específicos de pedido/cantidad se retiran durante una llamada que no los utiliza. Se evita así contaminación al reutilizar hilos.

MDC se asocia al hilo: no se propaga automáticamente a tareas de un executor. Quien despache una tarea asíncrona debe copiar e instalar el contexto y restaurar el del worker al terminar. La implementación actual no crea tareas asíncronas para notificar.

Un fallo de notificación se registra como WARN con `inventoryChangePreserved=true` y `retryScheduled=false`, incluyendo la excepción. La operación de inventario puede terminar con éxito porque la reserva se conservó; ese éxito no significa que se haya entregado el aviso.

El logging no constituye auditoría transaccional ni entrega garantizada. Los eventos de cambios se registran al aplicar el cambio bajo el bloqueo; no implican rollback ni persistencia. Logback es síncrono en esta configuración y los eventos de negocio pueden aumentar la duración de la sección exclusiva. Para alta carga hay que medir este coste y diseñar la recolección o publicación asíncrona con una política explícita de pérdida/backpressure.

## Verificación

Se agregaron once pruebas que capturan eventos reales de Logback y verifican correlación, restauración de contexto, aislamiento entre hilos, callbacks anidados, niveles, reintentos, identificación de vencimientos, fallos de notificación, excepciones inesperadas y sanitización de identificadores.

Suite completa: **31 pruebas aprobadas**, incluidas las veinte anteriores.

Referencias: [SLF4J y logging parametrizado](https://www.slf4j.org/manual.html), [MDC](https://www.slf4j.org/apidocs/org/slf4j/MDC.html), [configuración de Logback](https://logback.qos.ch/manual/configuration.html).
