package com.awareframework.micro

import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import java.net.URL

class WebsocketVerticle : AbstractVerticle() {

  private val logger = KotlinLogging.logger {}

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

        // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-http_changes-in-common-components#updates_in_http_methods_for_literal_websocket_literal
        vertx.createHttpServer()
          .webSocketHandler { server ->
            server.handler {
              logger.info { "Websocket connected" }
            }

            server.closeHandler {
              logger.info { "Websocket connection closed" }
            }

            server.textMessageHandler { message ->
              websocket.writeTextMessage(message)
            }

            websocket = server
          }
          .listen(getExternalWebSocketServerPort(serverConfig)) {
            if (it.failed()) {
              logger.error(it.cause()) { "Failed to initialise websocket server." }
            } else {
              logger.info { "AWARE Micro Websocket server: ws://${getExternalWebSocketServerHost(serverConfig)}:${getExternalWebSocketServerPort(serverConfig)}" }
            }
          }
      }
    }
  }

  private fun getExternalWebSocketServerHost(serverConfig: JsonObject): String {
    if (serverConfig.containsKey("external_server_host")) {
      return URL(serverConfig.getString("external_server_host")).host
    }
    return URL(serverConfig.getString("server_host")).host
  }

  private fun getExternalWebSocketServerPort(serverConfig: JsonObject): Int {
    if (serverConfig.containsKey("external_websocket_port")) {
      return serverConfig.getInteger("external_websocket_port")
    }
    return serverConfig.getInteger("websocket_port")
  }
}
