package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject

class MainVerticle : AbstractVerticle() {

  override fun start(startPromise: Promise<Void>) {
    val configReader = ConfigRetriever.create(vertx)
    configReader.getConfig {
      val parameters = it.result()
      initialise(parameters, startPromise)
    }
    configReader.listen {
      val parameters = it.newConfiguration
      vertx.close()
      initialise(parameters, startPromise)
    }
  }

  fun initialise(parameters : JsonObject, startPromise: Promise<Void>) {

    val serverWeb = parameters.getJsonObject("server").getJsonObject("web")
    val serverDatabase = parameters.getJsonObject("server").getJsonObject("database")
    val study = parameters.getJsonObject("study")

    vertx
      .createHttpServer()
      .requestHandler { req ->
        req.response()
          .putHeader("content-type", "application/json")
          .end(study.encodePrettily())
      }
      .listen(serverWeb.getInteger("port")) { http ->
        if (http.succeeded()) {
          startPromise.complete()
          println("HTTP server started on port ${serverWeb.getInteger("port")}")
        } else {
          startPromise.fail(http.cause());
        }
      }
  }
}
