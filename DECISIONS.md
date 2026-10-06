# Decisiones y supuestos

## Diseño

Se utiliza una arquitectura hexagonal ligera, explicada en [ARCHITECTURE.md](ARCHITECTURE.md). Se mantiene intacto `com.store.inventory.api` y la firma de `Inventory.create(Clock, StockAlertListener)`.

Cada llamada a la fábrica crea un inventario independiente. El almacenamiento y las políticas se inyectan mediante puertos; el dominio encapsula sus campos mutables. No hay dependencias adicionales de producción.

## Reintentos y estados

- `orderId` identifica globalmente un pedido de un producto. Un reintento con el mismo SKU y cantidad devuelve la reserva original, sin descontar ni extender el plazo. Un cambio de detalles genera `IllegalArgumentException`.
- Confirmar nuevamente una venta es idempotente. Confirmar un pedido desconocido o vencido genera `IllegalStateException`.
- Una reserva vence exactamente en `expiresAt`. Reintentar un pedido vencido devuelve su reserva original ya vencida; no se reactiva. Para volver a comprar se necesita otro identificador. El contrato no permite devolver un estado explícito y esta decisión debe acordarse con la app.
- Se conservan los pedidos terminales para impedir duplicados tardíos. La memoria crece con el historial; se necesita una política de retención antes de producción.
- El reloj es inyectado y se consulta dentro de la operación exclusiva. Las pruebas avanzan un reloj controlado, sin esperas reales para vencimientos.

## Stock y validación

- Vencimiento perezoso: antes de consultar, reservar, confirmar o reponer se liberan las reservas vencidas. No se necesita un hilo de fondo para que la siguiente compra encuentre las unidades disponibles.
- La cola procesa vencimientos por fecha. Consultar sin vencimientos pendientes es O(1); procesar k vencimientos cuesta O(k log n). Los pedidos confirmados permanecen en la cola hasta su fecha y entonces se descartan sin devolver unidades.
- Registrar otra vez el mismo producto y categoría es idempotente. Cambiar su categoría se rechaza.
- Identificadores nulos o vacíos y cantidades no positivas se rechazan. No se normalizan identificadores.
- Se contabilizan unidades disponibles y unidades no vendidas, incluidas las reservas. Una reposición que supera `Integer.MAX_VALUE` genera `ArithmeticException` sin aplicar la reposición; esto previene desbordamientos cuando luego vence una reserva.
- Los errores esperados se validan antes de cambiar el nuevo pedido. La liberación de reservas vencidas puede ocurrir incluso si la operación solicitada termina rechazada.

## Avisos

Se emite un aviso al reservar o reponer cuando quedan cinco unidades o menos. Registrar un producto con cero stock no avisa. Cada reposición positiva inicia un nuevo ciclo, aunque no supere el umbral. El vencimiento no reinicia el ciclo; confirmar no descuenta otra vez unidades disponibles.

Los avisos llevan una instantánea de disponibilidad. El callback se ejecuta fuera del bloqueo y puede recibir llamadas concurrentes o desordenadas. Una excepción de ejecución del listener se registra y no revierte la reserva. No se reintenta el aviso: la implementación es de mejor esfuerzo, con un intento por ciclo. El listener puede conectarse a un adaptador de correo o a uno que distribuya a distintos canales.

## Fuera del alcance

Persistencia, integración real de pagos/correo, API HTTP, autenticación, cancelaciones, configuración remota y reservas atómicas de carritos completos. El contrato define un producto por pedido. El bloqueo protege una instancia, no varios procesos.

## Antes de producción

1. Implementar persistencia transaccional del stock, reservas, estados e idempotencia. Usar una restricción única por `orderId` y bloqueo de filas o actualizaciones condicionales para evitar sobreventas entre instancias.
2. Ejecutar vencimientos con índices por estado/fecha y operaciones transaccionales que compitan correctamente con la confirmación; usar una fuente de tiempo coherente entre instancias.
3. Persistir eventos de aviso en una outbox en la misma transacción. Añadir reintentos e identificadores de evento para consumidores idempotentes.
4. Acordar pagos tardíos, reembolsos, respuesta de pedidos vencidos y retención de las claves de idempotencia.
5. Añadir métricas, pruebas con base de datos y carga. Validar los supuestos de aislamiento del adaptador persistente.

## Verificación

`mvn -o test` con Java 21: **20 pruebas, cero fallos y cero errores**, incluidos los tres tests originales. Se cubren límites, plazos, vencimiento exacto, reintentos, confirmaciones repetidas, validación, alertas por reposición, fallo del listener, desbordamiento, concurrencia, independencia entre instancias y sustitución de políticas.

Se fijan Maven Compiler 3.13.0 y Surefire 3.5.2. La ejecución offline utiliza las dependencias disponibles en la caché del entorno. En un entorno con Java 21 y acceso a Maven Central se puede ejecutar `mvn test`.
