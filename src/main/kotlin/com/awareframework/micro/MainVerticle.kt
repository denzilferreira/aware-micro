package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.SelfSignedCertificate
import io.vertx.ext.web.Router
import io.vertx.ext.web.common.template.TemplateEngine
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.pebble.PebbleTemplateEngine

class MainVerticle : AbstractVerticle() {

  lateinit var parameters: JsonObject

  override fun start(startPromise: Promise<Void>) {

    println("AWARE Micro: initializing...")

    val serverOptions = HttpServerOptions()
    val pebbleEngine: TemplateEngine = PebbleTemplateEngine.create(vertx)
    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())

    val configStore = ConfigStoreOptions().setType("json").setConfig(JsonObject().put("path", "./aware-micro.json"))
    val configRetrieverOptions = ConfigRetrieverOptions().addStore(configStore).setScanPeriod(5000)
    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)

    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        val parameters = config.result()
        val serverWeb = parameters.getJsonObject("server").getJsonObject("web")
        val serverDatabase = serverWeb.getJsonObject("database")
        val ssl = serverWeb.getJsonObject("ssl")

        if (ssl.getString("key_pem").isNotEmpty() && ssl.getString("cert_pem").isNotEmpty()) {
          serverOptions.pemKeyCertOptions =
            PemKeyCertOptions().setKeyPath(ssl.getString("key_pem")).setCertPath(ssl.getString("cert_pem"))
        } else {
          val selfSigned = SelfSignedCertificate.create()
          serverOptions.keyCertOptions = selfSigned.keyCertOptions()
        }
        serverOptions.isSsl = true

        val study = parameters.getJsonObject("study")
        study.put("title", "AWARE Micro")

        router.route(HttpMethod.GET, "/:studyKey").handler { route ->
          if (route.request().getParam("studyKey") == study.getString("studyKey")) {
            route.response().statusCode = 200

            val sensors = study.getJsonArray("sensors")
            sensors.add(
              JsonObject()
                .put("setting", "status_webservice")
                .put("value", true)
            )
            sensors.add(
              JsonObject()
                .put("setting", "webservice_server")
                .put(
                  "value",
                  "https://${serverWeb.getString("domain")}:${serverWeb.getInteger("port")}/${study.getString("studyKey")}"
                )
            )
            sensors.add(
              JsonObject()
                .put("setting", "study_id")
                .put("value", study.getString("studyKey"))
            )
            sensors.add(
              JsonObject()
                .put("setting", "study_start")
                .put("value", System.currentTimeMillis())
            )

            route.response().putHeader("content-type", "application/json").end(study.encode())
          } else {
            route.response().statusCode = 404
            route.response().end("Key matching: false")
          }
        }

        vertx.createHttpServer(serverOptions).requestHandler(router).listen(serverWeb.getInteger("port")) { server ->
          if (server.succeeded()) {
            startPromise.complete()
            println("AWARE Micro is available at https://${serverWeb.getString("domain")}:${serverWeb.getInteger("port")}")
          } else {
            startPromise.fail(server.cause());
            println("AWARE Micro failed: ${server.cause()}")
          }
        }
      } else { //this is a fresh instance, no server created yet.

        val selfSigned = SelfSignedCertificate.create()
        serverOptions.keyCertOptions = selfSigned.keyCertOptions()
        serverOptions.isSsl = true

        router.get("/").handler { request ->
          if (configStore.config.containsKey("user")) {
            //TODO ask credentials to open server
          } else {
            val data = JsonObject()
            data.put("title", "Step 1/4: Create account")
            pebbleEngine.render(data, "templates/create-account.peb") { pebble ->
              if (pebble.succeeded()) {
                request.response().statusCode = 200
                request.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
              }
            }
          }
        }

        router.post("/create-account").handler { request ->

          configStore.config
            .put("user", request.request().getParam("user"))
            .put("password", request.request().getParam("password"))
            .put("email", request.request().getParam("email"))

          val data = JsonObject()
          data.put("user", configStore.config.getValue("user"))
          data.put("title", "Step 2/4: Server information")

          pebbleEngine.render(data, "templates/create-server.peb") { pebble ->
            if (pebble.succeeded()) {
              request.response().statusCode = 200
              request.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
            } else {
              println("Failed: ${pebble.cause()}")
            }
          }
        }

        vertx.createHttpServer(serverOptions).requestHandler(router).listen(8000) { server ->
          if (server.succeeded()) {
            startPromise.complete()
            println("AWARE Micro is ready to configure at https://localhost:8000")
          } else {
            println("AWARE Micro failed: ${server.cause()}")
            startPromise.fail(server.cause());
          }
        }
      }
    }
  }
}
