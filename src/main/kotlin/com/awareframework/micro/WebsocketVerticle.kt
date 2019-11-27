package com.awareframework.micro

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import java.net.URL

class WebsocketVerticle : AbstractVerticle() {

  private lateinit var parameters: JsonObject
  private lateinit var websocket : ServerWebSocket

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

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

        vertx.createHttpServer()
          .websocketHandler { server ->
            server.handler {
              println("Websocket connected")
            }
            server.closeHandler {
              println("Websocket connection closed")
            }
            server.textMessageHandler { message ->
              websocket.writeTextMessage(message)
            }

            websocket = server
          }
          .listen(serverConfig.getInteger("websocket_port")) {
            if (it.failed()) {
              println("Failed to initialise websocket server ${it.cause().message}")
            } else {
              println("AWARE Micro Websocket server: ws://${URL(serverConfig.getString("server_host")).host}:${serverConfig.getInteger("websocket_port")}")
            }
          }
      }
    }
  }
}
