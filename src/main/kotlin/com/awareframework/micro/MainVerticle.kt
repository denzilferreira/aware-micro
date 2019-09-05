package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.SelfSignedCertificate
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler

class MainVerticle : AbstractVerticle() {

  override fun start(startPromise: Promise<Void>) {
    println("AWARE Micro: initializing...")

    val configReader = ConfigRetriever.create(vertx)
    configReader.getConfig {

      println("AWARE Micro: configuration found...")

      val parameters = it.result()

      println("AWARE Micro: ${parameters.encodePrettily()}")

      val serverWeb = parameters.getJsonObject("server").getJsonObject("web")
      val serverDatabase = serverWeb.getJsonObject("database")
      val ssl = serverWeb.getJsonObject("ssl")

      val serverOptions = HttpServerOptions()
      if (ssl.getString("key_pem").isNotEmpty() && ssl.getString("cert_pem").isNotEmpty()) {
        serverOptions.pemKeyCertOptions = PemKeyCertOptions().setKeyPath(ssl.getString("key_pem")).setCertPath(ssl.getString("cert_pem"))
      } else {
        val selfSigned = SelfSignedCertificate.create()
        serverOptions.keyCertOptions = selfSigned.keyCertOptions()
      }

      serverOptions.isSsl = true

      val study = parameters.getJsonObject("study")

      val router = Router.router(vertx)
      router.route().handler(BodyHandler.create())

      router.route("/").handler { route ->
        route.response().putHeader("content-type", "text/html").end("AWARE Micro is running!")
      }
      router.route("/:studyKey").handler { route ->
        if (route.request().getParam("studyKey") == study.getString("studyKey")) {
          route.response().statusCode = 200

          val sensors = study.getJsonArray("sensors")
          sensors.add(JsonObject()
            .put("setting", "status_webservice")
            .put("value", true)
          )
          sensors.add(JsonObject()
            .put("setting", "webservice_server")
            .put("value", "https://${serverWeb.getString("domain")}:${serverWeb.getInteger("port")}/${study.getString("studyKey")}")
          )
          sensors.add(JsonObject()
            .put("setting","study_id")
            .put("value",study.getString("studyKey"))
          )
          sensors.add(JsonObject()
            .put("setting","study_start")
            .put("value", System.currentTimeMillis())
          )

          route.response().putHeader("content-type","application/json").end(study.encode())
        } else {
          route.response().statusCode = 404
          route.response().end("Key matching: false")
        }
      }


//      {
//        "plugin": "",
//        "settings" : [
//        {
//          "setting": "",
//          "value": ""
//        }
//        ]
//      }

      vertx.createHttpServer(serverOptions)
        .requestHandler(router)
        .listen(serverWeb.getInteger("port")) { server ->
          if (server.succeeded()) {
            startPromise.complete()
            println("AWARE Micro is available at https://${serverWeb.getString("domain")}:${serverWeb.getInteger("port")}")
          } else {
            startPromise.fail(server.cause());
            println("AWARE Micro failed: ${server.cause()}")
          }
        }
    }
  }
}
