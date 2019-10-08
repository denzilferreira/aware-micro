package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.common.template.TemplateEngine
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.pebble.PebbleTemplateEngine
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class MainVerticle : AbstractVerticle() {

  lateinit var parameters: JsonObject

  override fun start(startPromise: Promise<Void>) {

    println("AWARE Micro: initializing...")

    val serverOptions = HttpServerOptions()
    val pebbleEngine: TemplateEngine = PebbleTemplateEngine.create(vertx)
    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())

    val configStoreOptions = ConfigStoreOptions()
    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      println("Loaded configuration: ${config.result()}")

      if (config.succeeded() && config.result().containsKey("server")) {

//        val parameters = config.result()
//        val serverWeb = parameters.getJsonObject("server").getJsonObject("web")
//        val serverDatabase = serverWeb.getJsonObject("database")
//        val ssl = serverWeb.getJsonObject("ssl")
//
//        if (ssl.getString("key_pem").isNotEmpty() && ssl.getString("cert_pem").isNotEmpty()) {
//          serverOptions.pemKeyCertOptions =
//            PemKeyCertOptions().setKeyPath(ssl.getString("key_pem")).setCertPath(ssl.getString("cert_pem"))
//        } else {
//          val selfSigned = SelfSignedCertificate.create()
//          serverOptions.keyCertOptions = selfSigned.keyCertOptions()
//        }
//        serverOptions.isSsl = true
//
//        val study = parameters.getJsonObject("study")
//        study.put("title", "AWARE Micro")
//
//        router.route(HttpMethod.GET, "/:studyKey").handler { route ->
//          if (route.request().getParam("studyKey") == study.getString("studyKey")) {
//            route.response().statusCode = 200
//
//            val sensors = study.getJsonArray("sensors")
//            sensors.add(
//              JsonObject()
//                .put("setting", "status_webservice")
//                .put("value", true)
//            )
//            sensors.add(
//              JsonObject()
//                .put("setting", "webservice_server")
//                .put(
//                  "value",
//                  "https://${serverWeb.getString("domain")}:${serverWeb.getInteger("port")}/${study.getString("studyKey")}"
//                )
//            )
//            sensors.add(
//              JsonObject()
//                .put("setting", "study_id")
//                .put("value", study.getString("studyKey"))
//            )
//            sensors.add(
//              JsonObject()
//                .put("setting", "study_start")
//                .put("value", System.currentTimeMillis())
//            )
//
//            route.response().putHeader("content-type", "application/json").end(study.encode())
//          } else {
//            route.response().statusCode = 404
//            route.response().end("Key matching: false")
//          }
//        }
//
//        vertx.createHttpServer(serverOptions).requestHandler(router).listen(serverWeb.getInteger("port")) { server ->
//          if (server.succeeded()) {
//            startPromise.complete()
//            println("AWARE Micro is available at https://${serverWeb.getString("domain")}:${serverWeb.getInteger("port")}")
//          } else {
//            startPromise.fail(server.cause());
//            println("AWARE Micro failed: ${server.cause()}")
//          }
//        }

      } else { //this is a fresh instance, no server created yet.

        val configFile = JsonObject()

        val server = JsonObject()
        server.put("database_engine", "mysql")
        server.put("database_name", "studyDatabase")
        server.put("database_user", "databaseUser")
        server.put("database_pwd", "databasePassword")
        server.put("database_port", 3306)
        server.put("api_port", 8080)
        server.put("study_key", "studyKey")

        configFile.put("server", server)

        val sensors = getSensors("https://raw.githubusercontent.com/denzilferreira/aware-client/master/aware-core/src/main/res/xml/aware_preferences.xml")
        configFile.put("sensors", sensors)

        val pluginsList = HashMap<String, String>()
        pluginsList["com.aware.plugin.ambient_noise"] = "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.ambient_noise/master/com.aware.plugin.ambient_noise/src/main/res/xml/preferences_ambient_noise.xml"
        pluginsList["com.aware.plugin.contacts_list"] = "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.contacts_list/master/com.aware.plugin.contacts_list/src/main/res/xml/preferences_contacts_list.xml"
        pluginsList["com.aware.plugin.device_usage"] = "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.device_usage/master/com.aware.plugin.device_usage/src/main/res/xml/preferences_device_usage.xml"
        pluginsList["com.aware.plugin.esm.scheduler"] = ""
        pluginsList["com.aware.plugin.fitbit"] = ""
        pluginsList["com.aware.plugin.google.activity_recognition"] = ""
        pluginsList["com.aware.plugin.google.auth"] = ""
        pluginsList["com.aware.plugin.google.fused_location"] = ""
        pluginsList["com.aware.plugin.openweather"] = ""
        pluginsList["com.aware.plugin.sensortag"] = ""
        pluginsList["com.aware.plugin.sentimental"] = ""
        pluginsList["com.aware.plugin.studentlife.audio_final"] = ""

        val plugins = getPlugins(pluginsList)
        configFile.put("plugins", plugins)

        val asyncFile = vertx.fileSystem().writeFile("./aware-config.json", Buffer.buffer(configFile.encodePrettily())) { result ->
          if(result.succeeded()) {
            println("You can now configure your server by editing the aware-config.json that was automatically created.")
          } else {
            println("Failed to create aware-config.json: ${result.cause()}")
          }

          stop()
        }

//        val selfSigned = SelfSignedCertificate.create()
//        serverOptions.keyCertOptions = selfSigned.keyCertOptions()
//        serverOptions.isSsl = true
//
//        router.get("/").handler { request ->
//          val data = JsonObject()
//          data.put("title", "Step 1/4: Create account")
//          pebbleEngine.render(data, "templates/create-account.peb") { pebble ->
//            if (pebble.succeeded()) {
//              request.response().statusCode = 200
//              request.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
//            }
//          }
//        }
//
//        router.post("/create-account").handler { request ->
//          val credentials = JsonObject()
//            .put("user", request.request().getParam("user"))
//            .put("password", request.request().getParam("password"))
//            .put("email", request.request().getParam("email"))
//          configStore.config.put("credentials", credentials)
//
//          val templateData = JsonObject()
//          templateData.put("user", configStore.config.getJsonObject("credentials").getString("user"))
//          templateData.put("title", "Step 2/4: RESTful API")
//          pebbleEngine.render(templateData, "templates/create-server.peb") { pebble ->
//            if (pebble.succeeded()) {
//              request.response().statusCode = 200
//              request.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
//            } else {
//              println("Failed: ${pebble.cause()}")
//            }
//          }
//        }
//
//        router.post("/create-server").handler { request ->
//          val server = JsonObject()
//            .put("domain", request.request().getParam("domain"))
//            .put("port", request.request().getParam("port"))
//            .put("ssl_privateKey", request.request().getParam("sslPrivate"))
//            .put("ssl_publicCert", request.request().getParam("sslPublicCert"))
//          configStore.config.put("server", server)
//
//          val templateData = JsonObject()
//          templateData.put("user", configStore.config.getJsonObject("credentials").getString("user"))
//          templateData.put("title", "Step 3/4: Database")
//
//          pebbleEngine.render(templateData, "templates/create-database.peb") { pebble ->
//            if (pebble.succeeded()) {
//              request.response().statusCode = 200
//              request.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
//            } else {
//              println("Failed: ${pebble.cause()}")
//            }
//          }
//        }
//
//        router.post("/create-database").handler { request ->
//          val database = JsonObject()
//          database.put("dbHost", request.request().getParam("dbHost"))
//          database.put("dbPort", request.request().getParam("dbPort"))
//          database.put("dbUser", request.request().getParam("dbUser"))
//          database.put("dbPassword", request.request().getParam("dbPassword"))
//          database.put("dbName", request.request().getParam("dbName"))
//          configStore.config.put("database", database)
//
//          val templateData = JsonObject()
//          templateData.put("title", "Step 4/4: Study")
//          templateData.put(
//            "sensors",
//            getSensors("https://raw.githubusercontent.com/denzilferreira/aware-client/master/aware-core/src/main/res/xml/aware_preferences.xml").toList()
//          )
//          templateData.put("plugins", getPlugins())
//          templateData.put("schedulers", getSchedulers())
//
//          pebbleEngine.render(templateData, "templates/create-study.peb") { pebble ->
//            if (pebble.succeeded()) {
//              request.response().statusCode = 200
//              request.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
//            } else {
//              println("Failed: ${pebble.cause()}")
//            }
//          }
//        }
//
//        vertx.createHttpServer(serverOptions)
//          .requestHandler(router)
//          .connectionHandler { httpConnection ->
//            println("Connected from: ${httpConnection.remoteAddress()}")
//          }
//          .listen(8000) { server ->
//            if (server.succeeded()) {
//              startPromise.complete()
//              println("AWARE Micro is ready to configure at https://localhost:8000")
//            } else {
//              println("AWARE Micro failed: ${server.cause()}")
//              startPromise.fail(server.cause());
//            }
//          }
//        println(getSensors("https://raw.githubusercontent.com/denzilferreira/aware-client/master/aware-core/src/main/res/xml/aware_preferences.xml").encodePrettily())
      }
    }
  }

  //TODO: make recursive version of xml parser as screens can have sub-sub-...-screens, generalisable for sensors and plugins.

  fun getSensors(xmlUrl: String): JsonArray {
    val sensors = JsonArray()
    val awarePreferences = URL(xmlUrl).openStream()

    val docFactory = DocumentBuilderFactory.newInstance()
    val docBuilder = docFactory.newDocumentBuilder()
    val doc = docBuilder.parse(awarePreferences)
    val docRoot = doc.getElementsByTagName("PreferenceScreen")

    for (i in 1..docRoot.length) {
      val child = docRoot.item(i)
      if (child != null) {

        val sensor = JsonObject()
        if (child.attributes.getNamedItem("android:key") != null)
          sensor.put("sensor", child.attributes.getNamedItem("android:key").nodeValue)
        if (child.attributes.getNamedItem("android:title") != null)
          sensor.put("title", child.attributes.getNamedItem("android:title").nodeValue)
        if (child.attributes.getNamedItem("android:icon") != null)
          sensor.put("icon", getSensorIcon(child.attributes.getNamedItem("android:icon").nodeValue))
        if (child.attributes.getNamedItem("android:summary") != null)
          sensor.put("summary", child.attributes.getNamedItem("android:summary").nodeValue)

        val settings = JsonArray()
        val subChildren = child.childNodes
        for (j in 0..subChildren.length) {
          val subChild = subChildren.item(j)
          if (subChild != null && subChild.nodeName.contains("Preference")) {
            val setting = JsonObject()
            if (subChild.attributes.getNamedItem("android:key") != null)
              setting.put("setting", subChild.attributes.getNamedItem("android:key").nodeValue)
            if (subChild.attributes.getNamedItem("android:title") != null)
              setting.put("title", subChild.attributes.getNamedItem("android:title").nodeValue)
            if (subChild.attributes.getNamedItem("android:defaultValue") != null)
              setting.put("defaultValue", subChild.attributes.getNamedItem("android:defaultValue").nodeValue)
            if (subChild.attributes.getNamedItem("android:summary") != null && subChild.attributes.getNamedItem("android:summary").nodeValue != "%s")
              setting.put("summary", subChild.attributes.getNamedItem("android:summary").nodeValue)

            settings.add(setting)
          }
        }
        sensor.put("settings", settings)
        sensors.add(sensor)
      }
    }
    return sensors
  }

  private fun getPlugins(xmlUrls : HashMap<String, String>): JsonArray {
    val plugins = JsonArray()

    for (pluginUrl in xmlUrls) {
      val pluginPreferences = URL(pluginUrl.value).openStream()

      //TODO parse settings, add to JSON
    }

    return plugins
  }

  private fun getSchedulers(): JsonArray {
    val schedulers = JsonArray()
    return schedulers
  }

  private fun getSensorIcon(drawableId: String): String {
    val icon = drawableId.substring(drawableId.indexOf('/') + 1)
    println("Processing $icon")

    val downloadUrl = "/denzilferreira/aware-client/raw/master/aware-phone/src/main/res/drawable/*.png"
    vertx.fileSystem()
      .open("src/main/resources/cache/$icon.png", OpenOptions().setCreate(true).setWrite(true)) { writeFile ->
        if (writeFile.succeeded()) {
          val asyncFile = writeFile.result()
          val webClientOptions = WebClientOptions()
            .setKeepAlive(true)
            .setPipelining(true)
            .setFollowRedirects(true)
            .setSsl(true)
            .setTrustAll(true)

          val client = WebClient.create(vertx, webClientOptions)
          client.get(443, "github.com", downloadUrl.replace("*", icon))
            .`as`(BodyCodec.pipe(asyncFile, true))
            .send { request ->
              if (request.succeeded()) {
                val iconFile = request.result()
                println("Requesting $icon.png: ${iconFile.statusCode()}")
              }
            }
        } else {
          println("Unable to create file: ${writeFile.cause()}")
        }
      }
    return "$icon.png"
  }
}
