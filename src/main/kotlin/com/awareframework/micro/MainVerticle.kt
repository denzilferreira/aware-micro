package com.awareframework.micro

import com.mitchellbosecke.pebble.PebbleEngine
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.*
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.templ.pebble.PebbleTemplateEngine
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.collections.HashMap

class MainVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject

  override fun start(startPromise: Promise<Void>) {

    println("AWARE Micro initializing...")

    val serverOptions = HttpServerOptions()
    val pebbleEngine = PebbleTemplateEngine.create(PebbleEngine.Builder().cacheActive(false).build())
    val eventBus = vertx.eventBus()

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())
    router.route("/cache/*").handler(StaticHandler.create("cache"))
    router.route().handler {
      println("Processing ${it.request().scheme()} ${it.request().method()} : ${it.request().path()}} \"with the following data ${it.request().params().toList()}")
      //"with the following data ${it.request().params().toList()}")
      it.next()
    }

    val configStore = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions()
      .addStore(configStore)
      .setScanPeriod(5000)

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->

      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")
        val study = parameters.getJsonObject("study")

        serverOptions.host = serverConfig.getString("server_host")

        /**
         * Generate QRCode to join the study using Google's Chart API
         */
        router.route(HttpMethod.GET, "/:studyNumber/:studyKey").handler { route ->
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            vertx.fileSystem().readFile("src/main/resources/cache/qrcode.png") { result ->
              //no QRCode yet
              if (result.failed()) {
                vertx.fileSystem()
                  .open("src/main/resources/cache/qrcode.png", OpenOptions().setCreate(true).setWrite(true)) { write ->
                    if (write.succeeded()) {
                      val asyncQrcode = write.result()
                      val webClientOptions = WebClientOptions()
                        .setKeepAlive(true)
                        .setPipelining(true)
                        .setFollowRedirects(true)
                        .setSsl(true)
                        .setTrustAll(true)

                      val client = WebClient.create(vertx, webClientOptions)
                      val serverURL =
                        "${serverConfig.getString("server_host")}:${serverConfig.getInteger("server_port")}/index.php/${study.getInteger(
                          "study_number"
                        )}/${study.getString("study_key")}"

                      println("URL encoded for the QRCode is: $serverURL")

                      client.get(
                        443, "chart.googleapis.com",
                        "/chart?chs=300x300&cht=qr&chl=$serverURL&choe=UTF-8"
                      )
                        .`as`(BodyCodec.pipe(asyncQrcode, true))
                        .send { request ->
                          if (request.succeeded()) {
                            pebbleEngine.render(JsonObject(), "templates/qrcode.peb") { pebble ->
                              if (pebble.succeeded()) {
                                route.response().statusCode = 200
                                route.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
                              }
                            }
                          } else {
                            println("QRCode creation failed: ${request.cause().message}")
                          }
                        }
                    }
                  }
              } else {
                //render cached QRCode

                val serverURL =
                  "${serverConfig.getString("server_host")}:${serverConfig.getInteger("server_port")}/index.php/${study.getInteger(
                    "study_number"
                  )}/${study.getString("study_key")}"

                pebbleEngine.render(JsonObject().put("studyURL", serverURL), "templates/qrcode.peb") { pebble ->
                  if (pebble.succeeded()) {
                    route.response().statusCode = 200
                    route.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
                  }
                }
              }
            }
          }
        }

        /**
         * This route is called:
         * - when joining the study, returns the JSON with all the settings from the study. Can be called from apps using Aware.joinStudy(URL) or client's QRCode scanner
         * - when checking study status with the study_check=1.
         */
        router.route(HttpMethod.POST, "/index.php/:studyNumber/:studyKey").handler { route ->
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            if (route.request().getFormAttribute("study_check") == "1") {
              val status = JsonObject()
              status.put("status", study.getBoolean("study_active"))
              status.put(
                "config",
                "[]"
              ) //NOTE: if we send the configuration, it will keep reapplying the settings on legacy clients. Sending empty JsonArray (i.e., no changes)
              route.response().end(JsonArray().add(status).encode())
              route.next()
            } else {
              println("Study configuration: ${getStudyConfig().encodePrettily()}")
              route.response().end(getStudyConfig().encode())
            }
          }
        }

        /**
         * Legacy: this will be hit by legacy client to retrieve the study information. It retuns JsonObject with (defined also in aware-config.json on AWARE Micro):
        {
        "study_key" : "studyKey",
        "study_number" : 1,
        "study_name" : "AWARE Micro demo study",
        "study_description" : "This is a demo study to test AWARE Micro",
        "researcher_first" : "First Name",
        "researcher_last" : "Last Name",
        "researcher_contact" : "your@email.com"
        }
         */
        router.route(HttpMethod.GET, "/index.php/webservice/client_get_study_info/:studyKey").handler { route ->
          if (route.request().getParam("studyKey") == study.getString("study_key")) {
            route.response().end(study.encode())
          }
        }

        router.route(HttpMethod.POST, "/index.php/:studyNumber/:studyKey/:table/:operation").handler { route ->
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            when (route.request().getParam("operation")) {
              "create_table" -> {
                eventBus.publish("createTable", route.request().getParam("table"))
                route.response().statusCode = 200
                route.response().end()
              }
              "insert" -> {
                eventBus.publish(
                  "insertData",
                  JsonObject()
                    .put("table", route.request().getParam("table"))
                    .put("device_id", route.request().getFormAttribute("device_id"))
                    .put("data", route.request().getFormAttribute("data"))
                )
                route.response().statusCode = 200
                route.response().end()
              }
              else -> {
                //no-op
                route.response().statusCode = 200
                route.response().end()
              }
            }
          }
        }

        /**
         * Default route, landing page of the server
         */
        router.route(HttpMethod.GET, "/").handler { route ->
          route.response().putHeader("content-type", "text/html").end(
            "Hello from AWARE Micro!<br/>Join study: <a href=\"${serverConfig.getString("server_host")}:${serverConfig.getInteger(
              "server_port"
            )}/${study.getInteger(
              "study_number"
            )}/${study.getString("study_key")}\">HERE</a>"
          )
        }

        //Use SSL either from pem certificates or self-signed (compatible with Android)
        if (serverConfig.getString("path_key_pem").isNotEmpty() && serverConfig.getString("path_cert_pem").isNotEmpty() && serverConfig.getString(
            "path_fullchain_pem"
          ).isNotEmpty()
        ) {
          serverOptions.pemTrustOptions = PemTrustOptions().addCertPath(serverConfig.getString("path_fullchain_pem"))
          serverOptions.pemKeyCertOptions = PemKeyCertOptions()
            .setKeyPath(serverConfig.getString("path_key_pem"))
            .setCertPath(serverConfig.getString("path_cert_pem"))
          serverOptions.isSsl = true
        }

        vertx.createHttpServer(serverOptions)
          .requestHandler(router)
          .listen(serverConfig.getInteger("server_port")) { server ->
            if (server.succeeded()) {
              when (serverConfig.getString("database_engine")) {
                "mysql" -> {
                  vertx.deployVerticle("com.awareframework.micro.MySQLVerticle")
                }
                "postgres" -> {
                  vertx.deployVerticle("com.awareframework.micro.PostgresVerticle")
                }
                else -> {
                  println("Not storing data into a database engine: mysql, postgres")
                }
              }

              vertx.deployVerticle("com.awareframework.micro.WebsocketVerticle")

              println("AWARE Micro API at ${serverConfig.getString("server_host")}:${serverConfig.getInteger("server_port")}")
              startPromise.complete()
            } else {
              println("AWARE Micro initialisation failed! Because: ${server.cause()}")
              startPromise.fail(server.cause());
            }
          }

      } else { //this is a fresh instance, no server created yet.

        val configFile = JsonObject()

        //infrastructure info
        val server = JsonObject()
        server.put("database_engine", "mysql") //[mysql, postgres]
        server.put("database_host", "localhost")
        server.put("database_name", "studyDatabase")
        server.put("database_user", "databaseUser")
        server.put("database_pwd", "databasePassword")
        server.put("database_port", 3306)
        server.put("server_host", "https://localhost")
        server.put("server_port", 8080)
        server.put("websocket_port", 8081)
        server.put("path_fullchain_pem", "")
        server.put("path_cert_pem", "")
        server.put("path_key_pem", "")
        configFile.put("server", server)

        //study info
        val study = JsonObject()
        study.put("study_key", "studyKey")
        study.put("study_number", 1)
        study.put("study_name", "AWARE Micro demo study")
        study.put("study_active", true)
        study.put("study_start", System.currentTimeMillis())
        study.put("study_description", "This is a demo study to test AWARE Micro")
        study.put("researcher_first", "First Name")
        study.put("researcher_last", "Last Name")
        study.put("researcher_contact", "your@email.com")
        configFile.put("study", study)

        //AWARE framework settings from both sensors and plugins
        val sensors =
          getSensors("https://raw.githubusercontent.com/denzilferreira/aware-client/master/aware-core/src/main/res/xml/aware_preferences.xml")

        configFile.put("sensors", sensors)

        val pluginsList = HashMap<String, String>()
        pluginsList["com.aware.plugin.ambient_noise"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.ambient_noise/master/com.aware.plugin.ambient_noise/src/main/res/xml/preferences_ambient_noise.xml"
        pluginsList["com.aware.plugin.contacts_list"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.contacts_list/master/com.aware.plugin.contacts_list/src/main/res/xml/preferences_contacts_list.xml"
        pluginsList["com.aware.plugin.device_usage"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.device_usage/master/com.aware.plugin.device_usage/src/main/res/xml/preferences_device_usage.xml"
        pluginsList["com.aware.plugin.esm.scheduler"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.esm.scheduler/master/com.aware.plugin.esm.scheduler/src/main/res/xml/preferences_esm_scheduler.xml"
        pluginsList["com.aware.plugin.fitbit"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.fitbit/master/com.aware.plugin.fitbit/src/main/res/xml/preferences_fitbit.xml"
        pluginsList["com.aware.plugin.google.activity_recognition"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.activity_recognition/master/com.aware.plugin.google.activity_recognition/src/main/res/xml/preferences_activity_recog.xml"
        pluginsList["com.aware.plugin.google.auth"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.auth/master/com.aware.plugin.google.auth/src/main/res/xml/preferences_google_auth.xml"
        pluginsList["com.aware.plugin.google.fused_location"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.fused_location/master/com.aware.plugin.google.fused_location/src/main/res/xml/preferences_fused_location.xml"
        pluginsList["com.aware.plugin.openweather"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.openweather/master/com.aware.plugin.openweather/src/main/res/xml/preferences_openweather.xml"
        pluginsList["com.aware.plugin.sensortag"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.sensortag/master/com.aware.plugin.sensortag/src/main/res/xml/preferences_sensortag.xml"
        pluginsList["com.aware.plugin.sentimental"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.sentimental/master/com.aware.plugin.sentimental/src/main/res/xml/preferences_sentimental.xml"
        pluginsList["com.aware.plugin.studentlife.audio_final"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.studentlife.audio_final/master/com.aware.plugin.studentlife.audio/src/main/res/xml/preferences_conversations.xml"

        val plugins = getPlugins(pluginsList)
        configFile.put("plugins", plugins)

        vertx.fileSystem().writeFile("./aware-config.json", Buffer.buffer(configFile.encodePrettily())) { result ->
          if (result.succeeded()) {
            println("You can now configure your server by editing the aware-config.json that was automatically created. You can now stop this instance (press Ctrl+C)")
          } else {
            println("Failed to create aware-config.json: ${result.cause()}")
          }
        }
      }
    }
  }

  /**
   * Check valid study key and number
   */
  fun validRoute(studyInfo: JsonObject, studyNumber: Int, studyKey: String): Boolean {
    return studyNumber == studyInfo.getInteger("study_number") && studyKey == studyInfo.getString("study_key")
  }

  fun getStudyConfig(): JsonArray {
    val serverConfig = parameters.getJsonObject("server")
    //println("Server config: ${serverConfig.encodePrettily()}")

    val study = parameters.getJsonObject("study")
    //println("Study info: ${study.encodePrettily()}")

    val sensors = JsonArray()
    val plugins = JsonArray()

    val awareSensors = parameters.getJsonArray("sensors")
    for (i in 0 until awareSensors.size()) {
      val awareSensor = awareSensors.getJsonObject(i)
      val sensorSettings = awareSensor.getJsonArray("settings")
      for (j in 0 until sensorSettings.size()) {
        val setting = sensorSettings.getJsonObject(j)

        val awareSetting = JsonObject()
        awareSetting.put("setting", setting.getString("setting"))

        when (setting.getString("setting")) {
          "status_webservice" -> awareSetting.put("value", "true")
          "webservice_server" -> awareSetting.put(
            "value",
            "${serverConfig.getString("server_host")}:${serverConfig.getInteger("server_port")}/index.php/${study.getInteger(
              "study_number"
            )}/${study.getString("study_key")}"
          )
          else -> awareSetting.put("value", setting.getString("defaultValue"))
        }
        sensors.add(awareSetting)
      }
    }

    var awareSetting = JsonObject()
    awareSetting.put("setting", "study_id")
    awareSetting.put("value", study.getString("study_key"))
    sensors.add(awareSetting)

    awareSetting = JsonObject()
    awareSetting.put("setting", "study_start")
    awareSetting.put("value", study.getDouble("study_start"))
    sensors.add(awareSetting)

    val awarePlugins = parameters.getJsonArray("plugins")
    for (i in 0 until awarePlugins.size()) {
      val awarePlugin = awarePlugins.getJsonObject(i)
      val pluginSettings = awarePlugin.getJsonArray("settings")

      val pluginOutput = JsonObject()
      pluginOutput.put("plugin", awarePlugin.getString("package_name"))

      val pluginSettingsOutput = JsonArray()
      for (j in 0 until pluginSettings.size()) {
        val setting = pluginSettings.getJsonObject(j)
        val settingOutput = JsonObject()
        settingOutput.put("setting", setting.getString("setting"))
        settingOutput.put("value", setting.getString("defaultValue"))
        pluginSettingsOutput.add(settingOutput)
      }
      pluginOutput.put("settings", pluginSettingsOutput)

      plugins.add(pluginOutput)
    }

    val output = JsonArray()
    output.add(JsonObject().put("sensors", sensors).put("plugins", plugins))
    return output
  }

  /**
   * This parses the aware-client xml file to retrieve all possible settings for a study
   */
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

            if (setting.containsKey("defaultValue"))
              settings.add(setting)
          }
        }
        sensor.put("settings", settings)
        sensors.add(sensor)
      }
    }
    return sensors
  }

  /**
   * This retrieves asynchronously the icons for each sensor from the client source code
   */
  private fun getSensorIcon(drawableId: String): String {
    val icon = drawableId.substring(drawableId.indexOf('/') + 1)
    val downloadUrl = "/denzilferreira/aware-client/raw/master/aware-core/src/main/res/drawable/*.png"

    vertx.fileSystem().mkdir("src/main/resources/cache") { result ->
      if (result.succeeded()) {
        println("Created cache folder")
      }
    }

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
                println("Cached $icon.png: ${iconFile.statusCode() == 200}")
              }
            }
        } else {
          println("Unable to create file: ${writeFile.cause()}")
        }
      }
    return "$icon.png"
  }

  /**
   * This parses a list of plugins' xml to retrieve plugins' settings
   */
  private fun getPlugins(xmlUrls: HashMap<String, String>): JsonArray {
    val plugins = JsonArray()

    for (pluginUrl in xmlUrls) {
      val pluginPreferences = URL(pluginUrl.value).openStream()

      val docFactory = DocumentBuilderFactory.newInstance()
      val docBuilder = docFactory.newDocumentBuilder()
      val doc = docBuilder.parse(pluginPreferences)
      val docRoot = doc.getElementsByTagName("PreferenceScreen")

      for (i in 0..docRoot.length) {
        val child = docRoot.item(i)
        if (child != null) {

          val plugin = JsonObject()
          plugin.put("package_name", pluginUrl.key)

          if (child.attributes.getNamedItem("android:key") != null)
            plugin.put("plugin", child.attributes.getNamedItem("android:key").nodeValue)
          if (child.attributes.getNamedItem("android:icon") != null)
            plugin.put("icon", child.attributes.getNamedItem("android:icon").nodeValue)
          if (child.attributes.getNamedItem("android:summary") != null)
            plugin.put("summary", child.attributes.getNamedItem("android:summary").nodeValue)

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

              if (setting.containsKey("defaultValue"))
                settings.add(setting)
            }
          }
          plugin.put("settings", settings)
          plugins.add(plugin)
        }
      }
    }
    return plugins
  }

  //TODO later with UI - maybe Raghu's new dashboard UI will handle this
  private fun getSchedulers(): JsonArray {
    val schedulers = JsonArray()
    return schedulers
  }
}
