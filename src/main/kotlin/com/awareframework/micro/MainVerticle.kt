package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.SelfSignedCertificate
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.templ.pebble.PebbleTemplateEngine
import io.vertx.kotlin.core.json.get
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class MainVerticle : AbstractVerticle() {

  lateinit var parameters: JsonObject

  override fun start(startPromise: Promise<Void>) {

    println("AWARE Micro: initializing...")

    val serverOptions = HttpServerOptions()
    val pebbleEngine = PebbleTemplateEngine.create(vertx)

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())
    router.route("/cache/*").handler(StaticHandler.create("cache"))

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

        val parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")

        println("Server config: ${serverConfig.encodePrettily()}")

        if (serverConfig.getString("key_pem").isNotEmpty() && serverConfig.getString("cert_pem").isNotEmpty()) {
          serverOptions.pemKeyCertOptions = PemKeyCertOptions()
            .setKeyPath(serverConfig.getString("key_pem"))
            .setCertPath(serverConfig.getString("cert_pem"))
        } else {
          val selfSigned = SelfSignedCertificate.create(serverConfig.getString("server_domain"))
          serverOptions.keyCertOptions = selfSigned.keyCertOptions()
          serverOptions.trustOptions = selfSigned.trustOptions()
        }
        serverOptions.isSsl = true
        serverOptions.host = serverConfig.getString("server_domain")

        val study = parameters.getJsonObject("study")

        println("Study info: ${study.encodePrettily()}")

        val sensors = JsonArray()
        val plugins = JsonArray()

        router.route(HttpMethod.GET, "/:studyNumber/:studyKey").handler { route ->

          if (route.request().getParam("studyNumber").toInt() == study.getInteger("study_number", 1)) {
            vertx.fileSystem()
              .open(
                "src/main/resources/cache/qrcode.png",
                OpenOptions().setCreate(true).setWrite(true).setTruncateExisting(true)
              ) { write ->
                if (write.succeeded()) {
                  val asyncQrcode = write.result()

                  val webClientOptions = WebClientOptions()
                    .setKeepAlive(true)
                    .setPipelining(true)
                    .setFollowRedirects(true)
                    .setSsl(true)
                    .setTrustAll(true)

                  val client = WebClient.create(vertx, webClientOptions)
                  client.get(
                    443, "chart.googleapis.com",
                    "/chart?chs=300x300&cht=qr&chl=https://${serverConfig.getString("server_domain")}:${serverConfig.getInteger(
                      "api_port"
                    )}/${study.getInteger("study_number")}/${study.getString("study_key")}&choe=UTF-8"
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
                        println("QRCode failed: ${request.cause().message}")
                      }
                    }
                }
              }
          }
        }

        router.route(HttpMethod.POST, "/:studyNumber/:studyKey").handler { route ->
          if (route.request().getParam("studyKey") == study.getString("study_key")
            && route.request().getParam("studyNumber").toInt() == study.getInteger("study_number")
          ) {

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
                    "https://${serverConfig.getString("server_domain")}:${serverConfig.getInteger("api_port")}/${study.getInteger(
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
            awareSetting.put("value", System.currentTimeMillis())
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
            route.response().end(output.encodePrettily())

          } else {
            route.response().statusCode = 404
            route.response().end("Invalid study key or number")
          }
        }

        router.route(HttpMethod.GET, "/").handler { route ->
          route.response().putHeader("content-type", "text").end("Hello from AWARE Micro - https://${serverConfig.getString("server_domain")}:${serverConfig.getInteger("api_port")}/${study.getInteger(
            "study_number"
          )}/${study.getString("study_key")}")
        }

        vertx.createHttpServer(serverOptions).requestHandler(router)
          .listen(serverConfig.getInteger("api_port")) { server ->
            if (server.succeeded()) {
              startPromise.complete()
              println(
                "AWARE Micro is available at https://${serverConfig.getString("server_domain")}:${serverConfig.getInteger(
                  "api_port"
                )}"
              )
            } else {
              startPromise.fail(server.cause());
              println("AWARE Micro failed: ${server.cause()}")
            }
          }

      } else { //this is a fresh instance, no server created yet.

        val configFile = JsonObject()

        //infrastructure info
        val server = JsonObject()
        server.put("database_engine", "mysql")
        server.put("database_name", "studyDatabase")
        server.put("database_user", "databaseUser")
        server.put("database_pwd", "databasePassword")
        server.put("cert_pem", "")
        server.put("key_pem", "")
        server.put("database_port", 3306)
        server.put("server_domain", "localhost")
        server.put("api_port", 8080)
        configFile.put("server", server)

        //study info
        val study = JsonObject()
        study.put("study_key", "studyKey")
        study.put("study_number", 1)
        study.put("study_name", "AWARE Micro demo study")
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
          sensor.put("icon", child.attributes.getNamedItem("android:icon").nodeValue)
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
