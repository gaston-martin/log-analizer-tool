## log-analizer-tool

Este proyecto tiene como finalidad identificar qué clases o funciones del código generan la mayor cantidad de logs, para poder optimizar el uso de los logs.
Es especialmente útil cuando se está excediendo el threshold de uso de logs en elastic y tenemos demasiadas líneas de logs para analizar. 

Se espera que al ejecutar esta herramienta, se puedan identificar clases o funciones que estén escribiendo información innecesaria en los logs, para poder de esta forma racionalizar el uso.

### Objetivos

* **Conectarse** a la **api** de [Elasticsearch](https://www.elastic.co/guide/en/elasticsearch/reference/5.6/search-uri-request.html) de un cluster determinado y obtener una **muestra** de los **logs** de una **aplicación**
* **Configurar** los parámetros mediante un **archivo de properties**
* **Procesar** la **muestra de logs** obtenida **generalizando** las líneas de log para que puedan ser **agrupadas** (_map/reduce_)
    * Mediante [regular expressions](https://regexr.com/) **configurables** con _capture groups_
    * **Aprovechando** la información que elastic **parsea** e indexa **como tags** (por ejemplo date, request_id, scope, etc) que **permite encontrar** luego dicha porción de **información** en el mensaje y **reemplazarla** por un **placeholder genérico** del nombre del tag.
    * **Transformar** todo **id numérico** (_user_id, reference_id, amount_, etc) en un **número único y genérico** como **999999**
    * Aplicar una **heurística** para **separar** ciertas **palabras** y **tokenizar** por separado los **ids numéricos**. Por ejemplo separar en **userid:12345** su parte **numérica**
* **Elaborar informes** estadísticos sobre el consumo de cada tipo de línea de log:
    * Ranking y % de **número de líneas** por **message**
    * Ranking y % de **número de líneas** por **source** (tags.source)
    * Ranking de peso (**cantidad de bytes**) por **message**
    * Ranking de peso (**cantidad de bytes**) por **source** (tags.source)
* **Escribir los informes** en sendos **archivos** para poder ser importados en planillas o analizados a posteriori
* Permitir **realimentar** las **expresiones regulares** mejorando el **agrupamiento** de líneas de log con **patrones comunes**

### Requisitos

* Se necesita tener instalada una **version de JDK >= Java 8**. Este proyecto fue desarrollado con la version 8.0.252-zulu en [SDKMAN](https://sdkman.io/)
* Deseable tener un **IDE para Java**, por ejemplo IntelliJ Idea Community Edition, Visual Studio, etc. 
* Estar conectado a la **VPN** para poder conectarse al cluster de elastic
* Tener instalada una version de **maven**. Para este proyecto se usó la version 3.6.3 en SDKMAN. La función de maven en este proyecto es la resolución de dependencias desde el pom.xml
 

### Configuración inicial

1. Clonarse o descargarse el **repo GIT** de este proyecto.
2. **Editar** el archivo `src/main/resources/config.properties`
3. **Ajustar** el **ELASTIC_HOSTNAME** para que apunte al **cluster correcto de elastic**.  Por ejemplo `ELASTIC_HOSTNAME=furyshort3.logs.furycloud.io
`
4. **Ajustar** el **nombre de la aplicación** para que coincida con el nombre de la app el PaaS por ejemplo `APP_NAME=fury-account-api` (Esto se usa para concatenar el path de acceso al index de elastic)
6. **Ajustar** el parámetro **MAX_RESULTS** que indica el tamaño máximo de logs a analizar. Por ejemplo `MAX_RESULTS=3000`
7. **Ajustar** el parámetro **PAGE_SIZE** que indica el tamaño de cada página a descargar de elastic. Por ejemplo `PAGE_SIZE=500`


### Ejecución

1. Invocar el metodo _main_ de la clase _com.gastonmartin.LogAnalizerApp_. En IntelliJ basta con ir hasta la clase, botón derecho, run.
2. **Revisar** los **archivos generados** en la raíz del proyecto:
    * `ranking_por_message_bytes.txt`
    * `ranking_por_message_count.txt`
    * `ranking_por_source_bytes.txt`
    * `ranking_por_source_count.txt`
3. Los **dos primeros archivos** dependen de las **expresiones regulares** definidas en `src/main/resources/expressions.txt`
4. Cuanto **mejores** sean las **expresiones regulares** definidas ahí, **mejor agrupamiento** hara la app de los logs, identificando líneas similares y dando un mejor reporte.
5. Mirando las **últimas líneas** de los dos primeros archivos (`ranking_por_message_*.txt`) se observarán **líneas muy parecidas**. Estas líneas son candidatas a **generar una regex** para contarlas como una sola línea en vez de N líneas distintas. **Esta es la clave de todo el proceso**.
6. En el archivo `expressions.txt` las _regex_ se definen de esta manera:
    * `regex|replacement_string`
    * Donde _regex_ es una **expresión regular de Java**, con **capture groups** indicados en **paréntesis**
    * y _replacement_string_ es el **statement de remplazo** de aquellos logs que _matcheen_ la regex. 
    * Dentro de la _replacement_string_, usamos **expresiones literales**, y **$1, $2, $3 ... $n** para referirte a los **capture groups** de la regex.
7. Por ejemplo la siguiente línea

   `(site_id = )(M..)|$1Mxx`
   
   Define **dos capture groups**:
   
   * El **primero** `(site_id = )` es un texto literal, **sin caracteres especiales** aparte de los **paréntesis** que definen el capture group
   * El **segundo** `(M..)` definen la letra M (literal) y otros dos caracteres cualquiera (comodines de regex) eso abarcará todos los sites de Meli.
   
   Luego del pipe `|` define la siguiente expresión
        
   * `$1` indica **copiar el primer capture group** (el texto literal)
   * `Mxx` indica escribir ese texto literalmente. 
   
   Es decir, cuando encuentra las líneas:
   
        site_id = MLA
        site_id = MCO
        site_id = MLB
   
   Siempre lo reemplazará por `site_id = Mxx` 




### Proceso iterativo de mejora de regular expressions

La clave para que el análisis sea exitoso consiste en ejecutar la app, analizar los archivos e ir armando regular expressions que agrupen logs similares
Agregar las regular expressions en el archivo expressions.txt
Y Luego reejecutar la app para obtener mejores reportes. 

A medida que se agrupan correctamente los logs, se tiene mejor información acerca de como se está utilizando el espacio en el índice de elastic. 

### Proceso rápido de caza de logs

Cuando no tenés tiempo de hacer una búsqueda iterativa mejorando las regex, se puede recurrir a los archivos
    
    ranking_por_source_bytes.txt
    ranking_por_source_count.txt

Estos archivos contienen un agrupamiento por el `tag.source`, que generalmente indica la clase desde la que se emite el log. 
Y esto permite identificar que clases están generando el mayor porcentaje de logs para atacar clase por clase. 

### Proceso combinado

Se puede partir de la búsqueda por `tags.source`, y una vez identificada la clase que más logs genera, se puede modificar LogProcessorService.process() para indicar los search terms "tags.source:NombreDelSource" para acotar la búsqueda a esa sola clase y luego encarar el enfoque línea por línea mejorando las regex

## Apéndices

#### A - JSON de ejemplo de una respuesta de Elastic

```
{
  "_index": "fury-account-api-2020.04.24",
  "_type": "log",
  "_id": "AXGrwWqsnalNzOuKawBK",
  "_score": 13.856795,
  "_source": {
    "timestamp": "2020-04-24T10:35:11.915Z",
    "tags": {
      "container": "application",
      "date": "2020-04-24_06:35:11",
      "process": "docker",
      "level": "ERROR",
      "source": "MovementController",
      "error": "negative_balance",
      "version": "0.275.0",
      "instance_id": "i-08d43a61cf192c3c2",
      "application": "account-api",
      "scope": "production-write",
      "host": "ip-10-50-225-82",
      "event": "multiPost",
      "request_id": "ae65ef32-2b1e-4f93-9826-87c12473c9ea",
      "status": "400"
    },
    "message": "[date: 2020-04-24_06:35:11] [level: ERROR] [source: MovementController] [request_id:ae65ef32-2b1e-4f93-9826-87c12473c9ea] [event:multiPost][status:400][error:negative_balance] KEY:multi-cb4af300dd05424bb87e68bb587e90a8_pay_4744914159-0 Negative balance "
  }
}

``` 

### B - Mejoras a la App

Cosas que me gustaría mejorarle a la app cuando tenga más tiempo: 

 * Mejor parametrización de comportamiento 
 * Opciones command line estilo gnu (--option=value)
 * Generación automática de regex en base a N líneas similares
 * Mejoras a la generalización de números 
 * Mejoras a los parámetros de muestreo 
    * Time boxing
    * Otros tipos de muestreo que no sean un cap del numero de linea
    * Filtros por distintos criterios
        * Source
        * Scope
        * Version
 * Generación de reportes formateados (HTML, Json, etc)
 * Mejoras a la integración con elastic
 * Fix del bug de too many open files



Dudas sugerencias, quejas, amenazas: 

[gastonm@gmail.com](mailto:gastonm@gmail.com)
