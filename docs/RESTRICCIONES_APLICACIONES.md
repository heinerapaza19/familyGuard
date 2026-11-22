# Restricciones de aplicaciones (Etapa 7)

FamilyGuard no bloquea ninguna aplicación por defecto. El padre o tutor elige una aplicación detectada y configura límite diario, bloqueo total y/o horarios. La identidad se basa en la ruta ejecutable exacta, no solo en el nombre.

## Prioridad

1. bloqueo total; 2. horario `BLOQUEAR`; 3. fuera de horarios `PERMITIR`; 4. límite diario; 5. advertencia; 6. permitido. Los horarios pueden cruzar medianoche.

## Agente y modo sin conexión

El agente evalúa la ventana activa cada dos segundos. Guarda reglas y eventos con DPAPI en `%USERPROFILE%\.familyguard-agent\restrictions.bin`, por lo que sobreviven a reinicios y funcionan sin red. Suma el consumo MySQL y los intervalos locales pendientes. Los eventos llevan UUID y se confirman idempotentemente.

Antes de cerrar comprueba PID, ruta, usuario Windows y procesos críticos. No cierra procesos de otro usuario, servicios esenciales, Explorer, componentes de inicio de sesión, el propio agente ni Java. Bloc de notas, Paint y Calculadora se permiten como objetivos no esenciales. El agente permanece visible y puede detenerse administrativamente.

La estrategia cierra el proceso activo y vuelve a evaluarlo tras cada reapertura. No impide que un administrador legítimo desinstale el agente. Procesos elevados que oculten ruta o usuario se rechazan. Aplicaciones con varios ejecutables se configuran por separado.

## API y persistencia

- `GET/POST /api/restrictions`, `PUT/DELETE /api/restrictions/{id}` para padres/tutores.
- `GET /api/agent/rules` y `POST /api/agent/restriction-events` mediante token del dispositivo.
- Flyway V5 amplía límites, horarios y auditoría sin borrar datos.

Las pruebas cubren límites, advertencias, horarios, contradicciones, aislamiento, caché DPAPI, reinicio lógico e idempotencia. Un bloqueo manual solo se considera exitoso si el PID objetivo termina realmente.
