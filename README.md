# event-rounting-libreria

Function App Java que actúa como **publicador (publisher)** de eventos hacia un **Azure Event Grid Topic**. Es la pieza que convierte una llamada HTTP del BFF en un evento de dominio que viaja por el broker hasta llegar a uno o más consumidores.

| Función | Tipo | Rol |
|---|---|---|
| `eventPublisher` | HTTP POST | Recibe `{eventType, subject, data}` y lo publica al Event Grid Topic `biblioteca-topics` |

---

## ¿Qué problema resuelve?

En un sistema event-driven, los productores de eventos no deberían acoplarse al SDK de Event Grid ni manejar credenciales del Topic. Esta función actúa como **gateway de publicación**: cualquier cliente HTTP (el BFF, una función externa, un script de testing) puede emitir un evento sin necesidad de:

- Conocer el endpoint del Topic
- Manejar el `AzureKeyCredential`
- Importar la SDK de Event Grid

Solo necesita hacer un POST con el payload del evento.

---

## ¿Qué la gatilla?

Un `HTTP POST` a su endpoint con un body JSON con la siguiente estructura:

```json
{
  "eventType": "string",   // tipo de evento (ej. "Prestamo.Creado")
  "subject": "string",     // sujeto del evento (ej. "biblioteca/prestamos/123")
  "data": { /* objeto */ } // payload arbitrario asociado al evento
}
```

Si el body está vacío o falta algún campo, se aplican defaults:
- `eventType` por defecto: `"Biblioteca.GenericEvent"`
- `subject` por defecto: `"biblioteca/generic"`
- `data` por defecto: el body completo o `{"mensaje": "evento sin contenido"}`

---

## ¿Qué retorna?

### ✅ Éxito (HTTP 200)

```json
{
  "mensaje": "Evento publicado correctamente",
  "eventType": "Prestamo.Creado",
  "subject": "biblioteca/prestamos/123"
}
```

### ❌ Error (HTTP 500)

Si las variables de entorno `EVENT_GRID_TOPIC_ENDPOINT` o `EVENT_GRID_TOPIC_KEY` no están configuradas, o si el Topic no responde:

```json
{
  "error": "Error al publicar evento",
  "detalle": "<mensaje de la excepción>"
}
```

---

## Function App en Azure

| Atributo | Valor |
|---|---|
| **Function App Name** | `functioneventrouting` |
| **Resource Group** | `rg_functions_bliblioteca` |
| **Región** | East US |
| **Runtime** | Java 21 |
| **Plan** | Consumption |

---

## URL del endpoint

### Local (`func start`)

```
http://localhost:7071/api/eventPublisher
```

### Local (Docker)

```
http://localhost:7071/api/eventPublisher
```
(Mapeando puerto: `docker run -p 7071:80 event-rounting-libreria`)

### Azure (producción)

```
https://functioneventrouting-<INSTANCE>.eastus-01.azurewebsites.net/api/eventPublisher?code=<REEMPLAZAR_FUNCTION_KEY>
```

> ⚠️ El parámetro `?code=...` es la **function key** de auth (`AuthorizationLevel.FUNCTION`). Necesario para invocar el endpoint en Azure. En local no se requiere.

---

## Conceptos clave de Azure Event Grid

### 📨 Topic

Un **Topic** es el "buzón" donde se publican los eventos. Los publishers envían eventos al Topic; los consumers se suscriben para recibirlos.

- **Nombre del Topic en este proyecto:** `biblioteca-topics`
- **Endpoint:** `https://biblioteca-topics.eastus-1.eventgrid.azure.net/api/events`
- **Key:** se obtiene desde Azure Portal → Event Grid Topic → Access keys

Cada evento publicado tiene la estructura estándar de Event Grid:

```json
{
  "id": "<UUID generado>",
  "eventType": "Prestamo.Creado",
  "subject": "biblioteca/prestamos/123",
  "data": { /* payload */ },
  "dataVersion": "1.0",
  "metadataVersion": "1",
  "eventTime": "2026-05-03T10:30:00Z",
  "topic": "/subscriptions/.../topics/biblioteca-topics"
}
```

### 🔔 Subscription (Event Subscription)

Una **Subscription** define qué consumer debe ser invocado cuando llega un evento al Topic. Un mismo Topic puede tener **múltiples Subscriptions** apuntando a distintos endpoints (Azure Functions, Webhooks, Service Bus, Storage Queues, etc.).

En este proyecto hay **tres Subscriptions** apuntando a distintos consumers del Function App `functionsbiblioteca` (fan-out):

| Name | Endpoint | Filtros (Event Types) |
|---|---|---|
| `duoc-subscripcion-cn2-libreria` | `functionsbiblioteca` → `notificacionConsumer` | `Prestamo.Creado`, `Prestamo.Devuelto`, `Usuario.EliminacionSolicitada` |
| `sub-prestamos-stock` | `functionsbiblioteca` → `prestamosStockConsumer` | `Prestamo.Creado`, `Prestamo.Devuelto` |
| `sub-usuario-eliminado` | `functionsbiblioteca` → `usuarioEliminadoConsumer` | `Usuario.EliminacionSolicitada` |

Sin estas Subscriptions, los eventos publicados se descartan silenciosamente — el Topic los recibe pero no tiene a quién entregárselos.

### 🔄 Patrón Pub/Sub

```
[Publisher: eventPublisher]
        ↓ sendEvent()
   [Topic: biblioteca-topics]
        ↓ fan-out (filtrado por Event Types en cada Subscription)
        ├─→ duoc-subscripcion-cn2-libreria → notificacionConsumer       → tabla NOTIFICACION
        ├─→ sub-prestamos-stock           → prestamosStockConsumer     → ajusta COPIAS_DISPONIBLE en LIBRO
        └─→ sub-usuario-eliminado         → usuarioEliminadoConsumer   → cascada (LIBRO + PRESTAMO + USUARIO + NOTIFICACION)
```

**Ventajas del modelo:**
- ✅ El publisher no sabe quién consume — desacoplamiento total.
- ✅ Se pueden agregar nuevos consumers sin tocar al publisher (fan-out).
- ✅ Si el consumer falla, Event Grid reintenta automáticamente con backoff exponencial.
- ✅ Si el consumer está caído, los eventos quedan en cola hasta 24 horas (configurable).

---

## Variables de entorno requeridas

En el recurso `functioneventrouting` (Azure Portal → Configuration → Application settings) o en `local.settings.json` para desarrollo local:

| Variable | Descripción | Ejemplo |
|---|---|---|
| `EVENT_GRID_TOPIC_ENDPOINT` | URL del Topic donde publicar | `https://biblioteca-topics.eastus-1.eventgrid.azure.net/api/events` |
| `EVENT_GRID_TOPIC_KEY` | Access key del Topic | `9ffR2jy3...` |
| `FUNCTIONS_WORKER_RUNTIME` | Runtime de Functions | `java` |
| `AzureWebJobsStorage` | Storage account (puede quedar vacío en local) | `""` |

---

## Configuración local (`local.settings.json`)

```json
{
  "IsEncrypted": false,
  "Values": {
    "AzureWebJobsStorage": "",
    "FUNCTIONS_WORKER_RUNTIME": "java",
    "EVENT_GRID_TOPIC_ENDPOINT": "https://<tu-topic>.<region>-1.eventgrid.azure.net/api/events",
    "EVENT_GRID_TOPIC_KEY": "<REEMPLAZAR_EVENT_GRID_TOPIC_KEY>"
  }
}
```

> Hay un `local.settings.example.json` con placeholders que sí se commitea al repo.

---

## Ejecutar localmente

```bash
mvn clean package
# Luego en VS Code: Tarea "func: host start"
# o desde terminal: cd target/azure-functions/functioneventrouting && func start
```

Base URL: `http://localhost:7071/api`

---

## Desplegar en Azure

```bash
mvn clean package azure-functions:deploy
```

> Si el Function App está stopped: `az functionapp start --name functioneventrouting --resource-group rg_functions_bliblioteca`.

---

## Docker

`Dockerfile` con build multi-stage usando la imagen oficial de Azure Functions Java 21. El `local.settings.json` se bundlea automáticamente al staging dir, por lo que no se requieren env vars en `docker run`.

```bash
docker build -t event-rounting-libreria .
docker run -p 7071:80 event-rounting-libreria
```

---

## Ejemplos de uso

> Reemplaza `{BASE}` por la URL local o de Azure (con `?code=...` si es Azure).

### Publicar `Prestamo.Creado`

```bash
curl -X POST {BASE}/eventPublisher \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "Prestamo.Creado",
    "subject": "biblioteca/prestamos/test-001",
    "data": {
      "id": "999",
      "idUsuario": "1",
      "idLibro": "5",
      "fechaPrestamo": "2026-05-03",
      "fechaDevolucion": "2026-05-17",
      "estado": "PRESTADO"
    }
  }'
```

### Publicar `Prestamo.Devuelto`

```bash
curl -X POST {BASE}/eventPublisher \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "Prestamo.Devuelto",
    "subject": "biblioteca/prestamos/test-001",
    "data": {
      "id": "999",
      "idUsuario": "1",
      "idLibro": "5",
      "estado": "DEVUELTO"
    }
  }'
```

### Publicar `Usuario.EliminacionSolicitada`

```bash
curl -X POST {BASE}/eventPublisher \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "Usuario.EliminacionSolicitada",
    "subject": "biblioteca/usuarios/5",
    "data": {
      "idUsuario": "5",
      "usuario": {
        "id": "5",
        "nombre": "Carlos",
        "apellidoPaterno": "Pérez",
        "apellidoMaterno": "García",
        "email": "carlos.perez@correo.cl"
      },
      "prestamos": [
        { "id": "10", "idLibro": "5", "estado": "PRESTADO" }
      ],
      "totalPrestamos": 1
    }
  }'
```

### Verificar que el evento fue procesado

A los 5-10 segundos (Event Grid es asíncrono), consultar el endpoint de notificaciones del otro Function App:

```bash
curl https://functionsbiblioteca-d4bpb6h8fybvbhac.eastus-01.azurewebsites.net/api/notificaciones
```

Si todo el flujo funciona, debe aparecer una nueva notificación con el `idUsuario` del payload.

---

## Estructura del proyecto

```
event-rounting-libreria/
├── src/main/java/cl/duoc/
│   └── Function.java                   # Handler eventPublisher
├── host.json
├── local.settings.json                 # Config local (NO commitear)
├── local.settings.example.json         # Plantilla
├── .env                                # Mirror de local.settings.json para .env-aware tools
├── .env.example                        # Plantilla
├── .gitignore
├── pom.xml
├── Dockerfile
└── README.md
```

---

## Troubleshooting

### Evento se publica OK pero no aparece notificación

Causa más probable: la **Event Subscription no está creada** o apunta a un consumer equivocado.

Verificar en Azure Portal:
1. Event Grid Topic `biblioteca-topics` → Subscriptions
2. Debe haber al menos una con endpoint = `functionsbiblioteca/notificacionConsumer`
3. Si la Subscription tiene filtros de Event Types, verificar que incluyan el tipo que estás publicando

### Error 500 con "EVENT_GRID_TOPIC_ENDPOINT y EVENT_GRID_TOPIC_KEY son obligatorios"

Las env vars no están configuradas en el Function App. Azure Portal → `functioneventrouting` → Configuration → Application settings → agregar.

### Error 401 al invocar en Azure

Falta el parámetro `?code=...` (function key) en la URL. Está disponible en Azure Portal → función `eventPublisher` → "Get Function URL".

### Métricas para verificar publicación

Azure Portal → Event Grid Topic `biblioteca-topics` → Metrics:
- **Published Events**: debe subir cada vez que llamas al publisher
- **Delivery Success Count**: debe subir si el consumer recibe OK
- **Delivery Failed Count**: si sube, hay error en el consumer (revisar Log Stream del consumer)
